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
import java.util.Comparator;
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

    /** Switches the bus PQ to PV, at the automaton's target in the given direction. */
    private static void switchPqPv(LfBus controllerBus, VoltageLimitDirection voltageLimitDirection, ReportNode reportNode) {
        controllerBus.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(true);
        double newTargetV = getSvcTargetV(controllerBus, voltageLimitDirection);
        controllerBus.getGeneratorVoltageControl().ifPresent(vc -> vc.setTargetValue(newTargetV));
        LfReports.reportStandByAutomatonActivation(reportNode, controllerBus.getId(), newTargetV);
        if (LOGGER.isTraceEnabled()) {
            if (voltageLimitDirection == VoltageLimitDirection.MAX) {
                LOGGER.trace("Switch bus '{}' PQ -> PV with high targetV={}", controllerBus.getId(), newTargetV);
            } else {
                LOGGER.trace("Switch bus '{}' PQ -> PV with low targetV={}", controllerBus.getId(), newTargetV);
            }
        }
    }

    private static double getBusV(LfBus bus) {
        return bus.getGeneratorVoltageControl().map(vc -> vc.getControlledBus().getV()).orElse(Double.NaN);
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

    /**
     * Gathers the buses that can switch, decides on the arrays alone, then applies. A PQ bus with a static var
     * compensator monitoring voltage is switched to PV when the voltage of the controlled bus is above the high
     * threshold or below the low threshold ({@link MonitoringVoltageDecisions}); every bus is read before any switch.
     */
    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        LfNetwork network = context.getNetwork();
        MonitoringVoltageVector m = gather(network);
        MonitoringVoltageDecisions.decideEntries(m);
        for (int i = 0; i < m.count; i++) {
            int decision = m.decision[i];
            LfBus bus = network.getBus(m.busNum[i]);
            if (MonitoringVoltageDecisions.switchesAtHigh(decision)) {
                switchPqPv(bus, VoltageLimitDirection.MAX, reportNode);
            }
            if (MonitoringVoltageDecisions.switchesAtLow(decision)) {
                switchPqPv(bus, VoltageLimitDirection.MIN, reportNode);
            }
        }
        return new OuterLoopResult(this, MonitoringVoltageDecisions.changesNetwork(m) ? OuterLoopStatus.UNSTABLE : OuterLoopStatus.STABLE);
    }

    /** The buses the check looks at: monitored controller buses with their voltage control off and a stand-by automaton. */
    static MonitoringVoltageVector gather(LfNetwork network) {
        MonitoringVoltageVector m = new MonitoringVoltageVector();
        boolean[] monitoredBuses = getMonitoredBuses(network);
        if (monitoredBuses == null) {
            m.reset(0);
            return m;
        }
        List<LfBus> buses = new ArrayList<>(2);
        List<Range<Double>> limits = new ArrayList<>(2);
        for (LfBus bus : getMonitoredControllerBuses(network, monitoredBuses)) {
            if (!bus.isGeneratorVoltageControlEnabled()) {
                getControlledBusVoltageLimits(bus).ifPresent(voltageLimits -> {
                    buses.add(bus);
                    limits.add(voltageLimits);
                });
            }
        }
        m.reset(buses.size());
        for (int i = 0; i < m.count; i++) {
            LfBus bus = buses.get(i);
            m.busNum[i] = bus.getNum();
            m.controlledV[i] = getBusV(bus);
            m.lowThreshold[i] = limits.get(i).getMinimum();
            m.highThreshold[i] = limits.get(i).getMaximum();
        }
        return m;
    }

    /** Needed when a monitored bus that holds its own voltage is a controller element of the generator voltage controls, with voltage limits. */
    @Override
    public boolean isNeeded(AcLoadFlowContext context) {
        LfNetwork network = context.getNetwork();
        boolean[] monitoredBuses = getMonitoredBuses(network);
        return monitoredBuses != null && getMonitoredControllerBuses(network, monitoredBuses).stream()
                .filter(LfBus::isGeneratorVoltageControlled)
                .anyMatch(bus -> getControlledBusVoltageLimits(bus).isPresent());
    }

    /**
     * The monitored buses that are controller elements of the generator voltage controls, in the order of
     * {@link LfNetwork#getControllerElements}: the few monitored buses are tested one by one, instead of computing the
     * controller elements of every voltage control of the network, after each change of the controls. A bus is one when
     * the main voltage control of its group is visible and counts it among its controller elements, and it is enabled;
     * they are listed by the controlled bus of that control, then in its order of the controller elements.
     */
    private static List<LfBus> getMonitoredControllerBuses(LfNetwork network, boolean[] monitoredBuses) {
        List<LfBus> buses = new ArrayList<>(2);
        for (int num = 0; num < monitoredBuses.length; num++) {
            if (monitoredBuses[num]) {
                LfBus bus = network.getBus(num);
                if (isControllerElement(bus)) {
                    buses.add(bus);
                }
            }
        }
        if (buses.size() > 1) {
            buses.sort(Comparator.<LfBus>comparingInt(bus -> mainControl(bus).getControlledBus().getNum())
                    .thenComparingInt(bus -> mainControl(bus).getMergedControllerElements().indexOf(bus)));
        }
        assert buses.equals(network.<LfBus>getControllerElements(VoltageControl.Type.GENERATOR).stream()
                .filter(bus -> monitoredBuses[bus.getNum()]).toList())
                : "The monitored buses tested one by one are not those of the controller elements";
        return buses;
    }

    private static GeneratorVoltageControl mainControl(LfBus bus) {
        return bus.getGeneratorVoltageControl().orElseThrow().getMainVoltageControl();
    }

    private static boolean isControllerElement(LfBus bus) {
        if (bus.isDisabled() || bus.getGeneratorVoltageControl().isEmpty()) {
            return false;
        }
        GeneratorVoltageControl main = mainControl(bus);
        return main.getMergeStatus() == VoltageControl.MergeStatus.MAIN && main.isVisible()
                && main.getMergedControllerElements().contains(bus);
    }
}
