/*
 * Copyright (c) 2022-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.lf.util.LfReports;
import com.powsybl.openloadflow.network.GeneratorVoltageControl;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfStaticVarCompensator;
import com.powsybl.openloadflow.network.VoltageControl;
import org.apache.commons.lang3.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class MonitoringVoltageOuterLoop implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitoringVoltageOuterLoop.class);

    public static final String NAME = "VoltageMonitoring";

    @Override
    public String getName() {
        return NAME;
    }

    private enum VoltageLimitDirection {
        MIN,
        MAX
    }

    private static final class PqToPvBus {

        private final LfBus controllerBus;

        private final VoltageLimitDirection voltageLimitDirection;

        private PqToPvBus(LfBus controllerBus, VoltageLimitDirection voltageLimitDirection) {
            this.controllerBus = controllerBus;
            this.voltageLimitDirection = voltageLimitDirection;
        }
    }

    private static void switchPqPv(List<PqToPvBus> pqToPvBuses, ReportNode reportNode) {
        for (PqToPvBus pqToPvBus : pqToPvBuses) {
            LfBus controllerBus = pqToPvBus.controllerBus;

            controllerBus.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(true);
            double newTargetV;
            if (pqToPvBus.voltageLimitDirection == VoltageLimitDirection.MAX
                    || pqToPvBus.voltageLimitDirection == VoltageLimitDirection.MIN) {
                newTargetV = getSvcTargetV(controllerBus, pqToPvBus.voltageLimitDirection);
                controllerBus.getGeneratorVoltageControl().ifPresent(vc -> vc.setTargetValue(newTargetV));
                LfReports.reportStandByAutomatonActivation(reportNode, controllerBus.getId(), newTargetV);
                if (LOGGER.isTraceEnabled()) {
                    if (pqToPvBus.voltageLimitDirection == VoltageLimitDirection.MAX) {
                        LOGGER.trace("Switch bus '{}' PQ -> PV with high targetV={}", controllerBus.getId(), newTargetV);
                    } else {
                        LOGGER.trace("Switch bus '{}' PQ -> PV with low targetV={}", controllerBus.getId(), newTargetV);
                    }
                }
            }
        }
    }

    private static double getBusV(LfBus bus) {
        return bus.getGeneratorVoltageControl().map(vc -> vc.getControlledBus().getV()).orElse(Double.NaN);
    }

    /**
     * A PQ bus with a static var compensator monitoring voltage can be switched to PV in 2 cases:
     *  - if the voltage of the controlled bus is higher than the high voltage threshold.
     *  - if the voltage of the controlled bus is lower that the low voltage threshold.
     */
    private static void checkPqBusForVoltageLimits(LfBus controllerCapableBus, List<PqToPvBus> pqToPvBuses, Range<Double> voltageLimit) {
        double busV = getBusV(controllerCapableBus);
        if (busV > voltageLimit.getMaximum()) {
            pqToPvBuses.add(new PqToPvBus(controllerCapableBus, VoltageLimitDirection.MAX));
        }
        if (busV < voltageLimit.getMinimum()) {
            pqToPvBuses.add(new PqToPvBus(controllerCapableBus, VoltageLimitDirection.MIN));
        }
    }

    private static Optional<LfStaticVarCompensator> findMonitoringSvc(LfBus bus) {
        return bus.getGenerators().stream()
                .filter(gen -> gen.getGeneratorControlType() == LfGenerator.GeneratorControlType.MONITORING_VOLTAGE)
                .findFirst()
                .map(LfStaticVarCompensator.class::cast);
    }

    private static Optional<Range<Double>> getControlledBusVoltageLimits(LfBus controllerCapableBus) {
        return findMonitoringSvc(controllerCapableBus)
                .flatMap(generator -> generator.getStandByAutomaton()
                        .map(automaton -> Range.of(automaton.getLowVoltageThreshold(), automaton.getHighVoltageThreshold())));
    }

    private static double getSvcTargetV(LfBus bus, VoltageLimitDirection direction) {
        return findMonitoringSvc(bus).flatMap(svc -> svc.getStandByAutomaton()
            .map(automaton -> {
                svc.setGeneratorControlType(LfGenerator.GeneratorControlType.VOLTAGE); // FIXME
                if (direction == VoltageLimitDirection.MIN) {
                    return automaton.getLowTargetV();
                } else {
                    return automaton.getHighTargetV();
                }
            })
        )
        .orElse(Double.NaN);
    }

    private static final String MONITORED_BUSES_PROPERTY = MonitoringVoltageOuterLoop.class.getName() + ".monitoredBuses";

    /**
     * For each bus of the network, whether it has a static var compensator with a stand-by automaton: the buses that
     * can monitor a voltage, as the device's list of monitoring controllers. The generators of a bus and the automaton
     * of a compensator do not change once the network is built, so they are gathered once per network, by bus number.
     * Whether a compensator monitors a voltage now, and whether its bus controls one, is read at each check.
     *
     * @return null when no bus can monitor a voltage
     */
    private static boolean[] getMonitoredBuses(LfNetwork network) {
        Object gathered = network.getProperty(MONITORED_BUSES_PROPERTY);
        if (gathered == null) {
            boolean[] monitored = new boolean[network.getBuses().size()];
            boolean any = false;
            for (LfBus bus : network.getBuses()) {
                for (LfGenerator generator : bus.getGenerators()) {
                    if (generator instanceof LfStaticVarCompensator svc && svc.getStandByAutomaton().isPresent()) {
                        monitored[bus.getNum()] = true;
                        any = true;
                    }
                }
            }
            gathered = any ? monitored : Boolean.FALSE;
            network.setProperty(MONITORED_BUSES_PROPERTY, gathered);
        }
        return gathered instanceof boolean[] monitored ? monitored : null;
    }

    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        OuterLoopStatus status = OuterLoopStatus.STABLE;

        List<PqToPvBus> pqToPvBuses = new ArrayList<>();
        boolean[] monitoredBuses = getMonitoredBuses(context.getNetwork());
        if (monitoredBuses != null) {
            for (LfBus bus : context.getNetwork().<LfBus>getControllerElements(VoltageControl.Type.GENERATOR)) {
                if (monitoredBuses[bus.getNum()] && !bus.isGeneratorVoltageControlEnabled()) {
                    getControlledBusVoltageLimits(bus).ifPresent(voltageLimits -> checkPqBusForVoltageLimits(bus, pqToPvBuses, voltageLimits));
                }
            }
        }

        if (!pqToPvBuses.isEmpty()) {
            switchPqPv(pqToPvBuses, reportNode);
            status = OuterLoopStatus.UNSTABLE;
        }

        return new OuterLoopResult(this, status);
    }

    /**
     * Needed when a monitored bus that holds its own voltage is a controller element of the generator voltage controls,
     * with voltage limits. The few monitored buses are tested one by one, instead of computing the controller elements
     * of every voltage control of the network: a bus is one when the main voltage control of its group is visible and
     * counts it among its controller elements, and it is enabled ({@link LfNetwork#getControllerElements}).
     */
    @Override
    public boolean isNeeded(AcLoadFlowContext context) {
        LfNetwork network = context.getNetwork();
        boolean[] monitoredBuses = getMonitoredBuses(network);
        if (monitoredBuses == null) {
            return false;
        }
        boolean needed = false;
        for (int num = 0; num < monitoredBuses.length && !needed; num++) {
            if (monitoredBuses[num]) {
                LfBus bus = network.getBus(num);
                needed = bus.isGeneratorVoltageControlled() && isControllerElement(bus)
                        && getControlledBusVoltageLimits(bus).isPresent();
            }
        }
        assert needed == network.<LfBus>getControllerElements(VoltageControl.Type.GENERATOR).stream()
                .filter(bus -> monitoredBuses[bus.getNum()])
                .filter(LfBus::isGeneratorVoltageControlled)
                .anyMatch(bus -> getControlledBusVoltageLimits(bus).isPresent())
                : "The monitored buses tested one by one are not those of the controller elements";
        return needed;
    }

    private static boolean isControllerElement(LfBus bus) {
        if (bus.isDisabled()) {
            return false;
        }
        GeneratorVoltageControl main = bus.getGeneratorVoltageControl().orElseThrow().getMainVoltageControl();
        return main.getMergeStatus() == VoltageControl.MergeStatus.MAIN && main.isVisible()
                && main.getMergedControllerElements().contains(bus);
    }
}
