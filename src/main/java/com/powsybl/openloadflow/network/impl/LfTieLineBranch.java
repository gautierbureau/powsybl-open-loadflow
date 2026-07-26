/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.sa.LimitReductionManager;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.results.BranchResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class LfTieLineBranch extends AbstractImpedantLfBranch {

    private final Ref<BoundaryLine> boundaryLine1Ref;

    private final Ref<BoundaryLine> boundaryLine2Ref;

    private final String id;

    private final String half1Id;

    private final String half2Id;

    /**
     * Electrical characteristics and nominal voltage of a half (boundary line) of the tie line,
     * cached at build time so the area interchange computation never goes back to the iidm
     * network (see the iidm free run phase of the multi thread copy mode).
     */
    public record HalfParams(double r, double x, double g, double b, double nominalV) {
    }

    private final HalfParams half1Params;

    private final HalfParams half2Params;

    // terminal nominal voltages, cached at build time so the branch results never go back to the
    // iidm network (see the iidm free run phase of the multi thread copy mode)
    private final double nominalV1;

    private final double nominalV2;

    protected LfTieLineBranch(LfNetwork network, LfBus bus1, LfBus bus2, PiModel piModel, TieLine tieLine, LfNetworkParameters parameters) {
        super(network, bus1, bus2, piModel, parameters);
        this.boundaryLine1Ref = Ref.create(tieLine.getBoundaryLine1(), parameters.isCacheEnabled());
        this.boundaryLine2Ref = Ref.create(tieLine.getBoundaryLine2(), parameters.isCacheEnabled());
        this.id = tieLine.getId();
        this.half1Id = tieLine.getBoundaryLine1().getId();
        this.half2Id = tieLine.getBoundaryLine2().getId();
        this.nominalV1 = tieLine.getBoundaryLine1().getTerminal().getVoltageLevel().getNominalV();
        this.nominalV2 = tieLine.getBoundaryLine2().getTerminal().getVoltageLevel().getNominalV();
        BoundaryLine half1 = tieLine.getBoundaryLine1();
        BoundaryLine half2 = tieLine.getBoundaryLine2();
        this.half1Params = new HalfParams(half1.getR(), half1.getX(), half1.getG(), half1.getB(), nominalV1);
        this.half2Params = new HalfParams(half2.getR(), half2.getX(), half2.getG(), half2.getB(), nominalV2);
    }

    protected LfTieLineBranch(LfTieLineBranch other, LfNetwork network, LfBus bus1, LfBus bus2) {
        super(other, network, bus1, bus2);
        this.boundaryLine1Ref = other.boundaryLine1Ref;
        this.boundaryLine2Ref = other.boundaryLine2Ref;
        this.id = other.id;
        this.half1Id = other.half1Id;
        this.half2Id = other.half2Id;
        this.half1Params = other.half1Params;
        this.half2Params = other.half2Params;
        this.nominalV1 = other.nominalV1;
        this.nominalV2 = other.nominalV2;
    }

    public static LfTieLineBranch create(TieLine line, LfNetwork network, LfBus bus1, LfBus bus2, LfNetworkParameters parameters) {
        Objects.requireNonNull(line);
        Objects.requireNonNull(network);
        Objects.requireNonNull(parameters);
        double nominalV2 = line.getBoundaryLine2().getTerminal().getVoltageLevel().getNominalV();
        double zb = PerUnit.zb(nominalV2);
        PiModel piModel = new SimplePiModel()
                .setR1(1 / Transformers.getRatioPerUnitBase(line))
                .setR(line.getR() / zb)
                .setX(line.getX() / zb)
                .setG1(line.getG1() * zb)
                .setG2(line.getG2() * zb)
                .setB1(line.getB1() * zb)
                .setB2(line.getB2() * zb);
        return new LfTieLineBranch(network, bus1, bus2, piModel, line, parameters);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public List<String> getOriginalIds() {
        return List.of(id, half1Id, half2Id);
    }

    @Override
    public BranchType getBranchType() {
        return BranchType.TIE_LINE;
    }

    public BoundaryLine getHalf1() {
        return boundaryLine1Ref.get();
    }

    public HalfParams getHalf1Params() {
        return half1Params;
    }

    public HalfParams getHalf2Params() {
        return half2Params;
    }

    public BoundaryLine getHalf2() {
        return boundaryLine2Ref.get();
    }

    @Override
    public List<BranchResult> createBranchResult(double preContingencyBranchP1, double preContingencyBranchOfContingencyP1,
                                                 boolean createExtension, Map<String, LfBranchResults> zeroImpedanceFlows,
                                                 LoadFlowModel loadFlowModel) {
        double currentScale1 = PerUnit.ib(nominalV1);
        double currentScale2 = PerUnit.ib(nominalV2);

        var branchResult = buildBranchResult(loadFlowModel, zeroImpedanceFlows, currentScale1, currentScale2, preContingencyBranchP1, preContingencyBranchOfContingencyP1);

        var half1Result = new BranchResult(half1Id, branchResult.getP1(), branchResult.getQ1(), branchResult.getI1(), Double.NaN, Double.NaN, Double.NaN, branchResult.getFlowTransfer());
        var half2Result = new BranchResult(half2Id, branchResult.getP2(), branchResult.getQ2(), branchResult.getI2(), Double.NaN, Double.NaN, Double.NaN, branchResult.getFlowTransfer());
        if (createExtension) {
            branchResult.addExtension(OlfBranchResult.class, new OlfBranchResult(piModel.getR1(), piModel.getContinuousR1(),
                    getV1() * nominalV1, getV2() * nominalV2, Math.toDegrees(getAngle1()), Math.toDegrees(getAngle2())));
            half1Result.addExtension(OlfBranchResult.class, new OlfBranchResult(piModel.getR1(), piModel.getContinuousR1(),
                    getV1() * nominalV1, Double.NaN, Math.toDegrees(getAngle1()), Double.NaN));
            half2Result.addExtension(OlfBranchResult.class, new OlfBranchResult(piModel.getR1(), piModel.getContinuousR1(),
                    Double.NaN, getV2() * nominalV2, Double.NaN, Math.toDegrees(getAngle2())));
        }
        return List.of(branchResult, half1Result, half2Result); // make sure to put the tie-line first in the list, used in post-contingency flow filtering
    }

    private <T extends LoadingLimits> Supplier<Map<String, T>> toMapIndexedByOperationalLimitsGroupId(Function<OperationalLimitsGroup, Optional<T>> limitsGetter, TwoSides side) {
        return () -> (side == TwoSides.ONE ? getHalf1() : getHalf2())
                .getAllSelectedOperationalLimitsGroups()
                .stream()
                .filter(o -> limitsGetter.apply(o).isPresent())
                .collect(Collectors.toMap(OperationalLimitsGroup::getId, o -> limitsGetter.apply(o).orElseThrow()));
    }

    @Override
    public List<LfLimitsGroup> getLimits1(final LimitType type, LimitReductionManager limitReductionManager) {
        List<LfLimitsGroup> cached = getCachedLimits1(type);
        if (cached != null) {
            return cached;
        }
        switch (type) {
            case ACTIVE_POWER:
                return getLimits1(type, toMapIndexedByOperationalLimitsGroupId(OperationalLimitsGroup::getActivePowerLimits, TwoSides.ONE), limitReductionManager);
            case APPARENT_POWER:
                return getLimits1(type, toMapIndexedByOperationalLimitsGroupId(OperationalLimitsGroup::getApparentPowerLimits, TwoSides.ONE), limitReductionManager);
            case CURRENT:
                return getLimits1(type, toMapIndexedByOperationalLimitsGroupId(OperationalLimitsGroup::getCurrentLimits, TwoSides.ONE), limitReductionManager);
            case VOLTAGE:
            default:
                throw new UnsupportedOperationException(String.format("Getting %s limits is not supported.", type.name()));
        }
    }

    @Override
    public List<LfLimitsGroup> getLimits2(final LimitType type, LimitReductionManager limitReductionManager) {
        List<LfLimitsGroup> cached = getCachedLimits2(type);
        if (cached != null) {
            return cached;
        }
        switch (type) {
            case ACTIVE_POWER:
                return getLimits2(type, toMapIndexedByOperationalLimitsGroupId(OperationalLimitsGroup::getActivePowerLimits, TwoSides.TWO), limitReductionManager);
            case APPARENT_POWER:
                return getLimits2(type, toMapIndexedByOperationalLimitsGroupId(OperationalLimitsGroup::getApparentPowerLimits, TwoSides.TWO), limitReductionManager);
            case CURRENT:
                return getLimits2(type, toMapIndexedByOperationalLimitsGroupId(OperationalLimitsGroup::getCurrentLimits, TwoSides.TWO), limitReductionManager);
            case VOLTAGE:
            default:
                throw new UnsupportedOperationException(String.format("Getting %s limits is not supported.", type.name()));
        }
    }

    @Override
    public double[] getLimitReductions(TwoSides side, LimitReductionManager limitReductionManager, LoadingLimits limits) {
        return new double[] {};
    }

    @Override
    public void updateState(LfNetworkStateUpdateParameters parameters, LfNetworkUpdateReport updateReport) {
        updateFlows(p1.eval(), q1.eval(), p2.eval(), q2.eval());
    }

    @Override
    public void updateFlows(double p1, double q1, double p2, double q2) {
        getHalf1().getTerminal().setP(p1 * PerUnit.SB)
                .setQ(q1 * PerUnit.SB);
        getHalf2().getTerminal().setP(p2 * PerUnit.SB)
                .setQ(q2 * PerUnit.SB);
    }

    @Override
    public boolean hasPhaseControllerCapability() {
        return false;
    }
}
