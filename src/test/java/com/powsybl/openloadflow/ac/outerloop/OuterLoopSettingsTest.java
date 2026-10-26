/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.solver.DefaultNewtonRaphsonStoppingCriteria;
import com.powsybl.openloadflow.ac.solver.NewtonRaphsonParameters;
import com.powsybl.openloadflow.ac.solver.NewtonRaphsonStoppingCriteria;
import com.powsybl.openloadflow.ac.solver.NewtonRaphsonStoppingCriteriaType;
import com.powsybl.openloadflow.ac.solver.PerEquationTypeStoppingCriteria;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.util.PerUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The outer loops and the stopping criteria of a run expose the settings the run created them with.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class OuterLoopSettingsTest {

    private static AcLoadFlowParameters createAcParameters(LoadFlowParameters parameters) {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        return OpenLoadFlowParameters.createAcParameters(network, parameters, OpenLoadFlowParameters.get(parameters),
                new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
    }

    private static <T extends AcOuterLoop> T outerLoop(AcLoadFlowParameters acParameters, Class<T> type) {
        return acParameters.getOuterLoops().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    private static NewtonRaphsonStoppingCriteria stoppingCriteria(AcLoadFlowParameters acParameters) {
        return ((NewtonRaphsonParameters) acParameters.getAcSolverParameters()).getStoppingCriteria();
    }

    @Test
    void testIncrementalControlsWithUniformCriteria() {
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD)
                .setTransformerVoltageControlOn(true)
                .setShuntCompensatorVoltageControlOn(true);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusPMaxMismatch(0.5)
                .setUseActiveLimits(false)
                .setReactiveLimitsMaxPqPvSwitch(7)
                .setNewtonRaphsonConvEpsPerEq(1e-5)
                .setTransformerVoltageControlMode(OpenLoadFlowParameters.TransformerVoltageControlMode.INCREMENTAL_VOLTAGE_CONTROL)
                .setTransformerReactivePowerControl(true)
                .setIncrementalTransformerRatioTapControlOuterLoopMaxTapShift(4)
                .setShuntVoltageControlMode(OpenLoadFlowParameters.ShuntVoltageControlMode.INCREMENTAL_VOLTAGE_CONTROL)
                .setIncrementalShuntControlOuterLoopMaxSectionShift(5);
        AcLoadFlowParameters acParameters = createAcParameters(parameters);

        DistributedSlackOuterLoop distributedSlack = outerLoop(acParameters, DistributedSlackOuterLoop.class);
        assertEquals(0.5, distributedSlack.getSlackBusPMaxMismatch());
        assertEquals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, distributedSlack.getActivePowerDistribution().getBalanceType());
        assertFalse(distributedSlack.getActivePowerDistribution().isUseActiveLimits());
        ReactiveLimitsOuterLoop reactiveLimits = outerLoop(acParameters, ReactiveLimitsOuterLoop.class);
        assertEquals(7, reactiveLimits.getMaxPqPvSwitch());
        assertEquals(1e-5, reactiveLimits.getMaxReactivePowerMismatch()); // the uniform threshold
        assertEquals(4, outerLoop(acParameters, IncrementalTransformerVoltageControlOuterLoop.class).getMaxTapShift());
        assertEquals(4, outerLoop(acParameters, IncrementalTransformerReactivePowerControlOuterLoop.class).getMaxTapShift());
        assertEquals(5, outerLoop(acParameters, IncrementalShuntVoltageControlOuterLoop.class).getMaxSectionShift());
        assertEquals(1e-5, assertInstanceOf(DefaultNewtonRaphsonStoppingCriteria.class, stoppingCriteria(acParameters)).getConvEpsPerEq());
    }

    @Test
    void testTransformerVoltageControlAfterGeneratorVoltageControl() {
        LoadFlowParameters parameters = new LoadFlowParameters().setTransformerVoltageControlOn(true);
        OpenLoadFlowParameters.create(parameters)
                .setTransformerVoltageControlMode(OpenLoadFlowParameters.TransformerVoltageControlMode.AFTER_GENERATOR_VOLTAGE_CONTROL)
                .setTransformerVoltageControlUseInitialTapPosition(true)
                .setGeneratorVoltageControlMinNominalVoltage(42);
        TransformerVoltageControlOuterLoop transformerVoltageControl = outerLoop(createAcParameters(parameters), TransformerVoltageControlOuterLoop.class);
        assertTrue(transformerVoltageControl.isUseInitialTapPosition());
        assertEquals(42, transformerVoltageControl.getMaxControlledNominalVoltageOverride());
    }

    @Test
    void testPerEquationTypeCriteria() {
        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters)
                .setNewtonRaphsonStoppingCriteriaType(NewtonRaphsonStoppingCriteriaType.PER_EQUATION_TYPE_CRITERIA)
                .setNewtonRaphsonConvEpsPerEq(1e-5)
                .setMaxActivePowerMismatch(0.011)
                .setMaxReactivePowerMismatch(0.012)
                .setMaxVoltageMismatch(1.3e-4)
                .setMaxAngleMismatch(1.4e-5)
                .setMaxRatioMismatch(1.5e-5)
                .setMaxSusceptanceMismatch(1.6e-4);
        AcLoadFlowParameters acParameters = createAcParameters(parameters);

        PerEquationTypeStoppingCriteria criteria = assertInstanceOf(PerEquationTypeStoppingCriteria.class, stoppingCriteria(acParameters));
        assertEquals(1e-5, criteria.getConvEpsPerEq());
        assertEquals(0.011, criteria.getMaxActivePowerMismatch());
        assertEquals(0.012, criteria.getMaxReactivePowerMismatch());
        assertEquals(1.3e-4, criteria.getMaxVoltageMismatch());
        assertEquals(1.4e-5, criteria.getMaxDefaultAngleMismatch());
        assertEquals(1.5e-5, criteria.getMaxDefaultRatioMismatch());
        assertEquals(1.6e-4, criteria.getMaxDefaultSusceptanceMismatch());
        // the reactive limits loop switches outside the reactive power threshold, in per unit
        assertEquals(0.012 / PerUnit.SB, outerLoop(acParameters, ReactiveLimitsOuterLoop.class).getMaxReactivePowerMismatch());
    }

    @Test
    void testAreaInterchangeControl() {
        LoadFlowParameters parameters = new LoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        OpenLoadFlowParameters.create(parameters)
                .setAreaInterchangeControl(true)
                .setSlackBusPMaxMismatch(0.5)
                .setAreaInterchangePMaxMismatch(3)
                .setUseActiveLimits(false);
        AcAreaInterchangeControlOuterLoop areaInterchangeControl = outerLoop(createAcParameters(parameters), AcAreaInterchangeControlOuterLoop.class);
        assertEquals(0.5, areaInterchangeControl.getSlackBusPMaxMismatch());
        assertEquals(3, areaInterchangeControl.getAreaInterchangePMaxMismatch());
        assertEquals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, areaInterchangeControl.getActivePowerDistribution().getBalanceType());
        assertFalse(areaInterchangeControl.getActivePowerDistribution().isUseActiveLimits());
    }
}
