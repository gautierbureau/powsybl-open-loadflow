package com.powsybl.openloadflow.sensi;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.NetworkCache;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.VoltageControlNetworkFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Probe: what exactly changes in the equation system when transformer voltage control is switched on. */
class EquationSwapProbe {

    private static Map<String, Integer> snapshot(NetworkCache.AcLfValue value) {
        Map<String, Integer> byType = new LinkedHashMap<>();
        value.getContext().getEquationSystem().getIndex().getSortedSingleEquationsToSolve()
                .forEach(eq -> byType.merge(eq.getType().toString(), 1, Integer::sum));
        return byType;
    }

    private void probe(String label, Network network, OpenLoadFlowParameters.TransformerVoltageControlMode mode) {
        LoadFlowParameters lfp = new LoadFlowParameters().setTransformerVoltageControlOn(true);
        OpenLoadFlowParameters.create(lfp).setNetworkCacheEnabled(true).setTransformerVoltageControlMode(mode);
        if (!LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged()) {
            System.out.println("\n### " + label + " / " + mode + " : DID NOT CONVERGE");
            return;
        }
        var value = NetworkCache.AC_LF_INSTANCE.findEntry(network).orElseThrow().getValues().get(0);
        var lfNetwork = value.getNetwork();

        Map<String, Integer> a = snapshot(value);
        int rowsA = value.getContext().getEquationSystem().getIndex().getColumnCount();
        long controllers = lfNetwork.getBranches().stream().filter(b -> b.getVoltageControl().isPresent()).count();

        for (LfBranch b : lfNetwork.getBranches()) {
            b.getVoltageControl().ifPresent(vc -> b.setVoltageControlEnabled(true));
        }
        lfNetwork.fixTransformerVoltageControls();

        Map<String, Integer> b2 = snapshot(value);
        int rowsB = value.getContext().getEquationSystem().getIndex().getColumnCount();

        System.out.printf("%n### %s / %s : %d controller branch(es), rows %d -> %d%n",
                label, mode, controllers, rowsA, rowsB);
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b2.keySet());
        for (String k : keys) {
            int d = b2.getOrDefault(k, 0) - a.getOrDefault(k, 0);
            if (d != 0) {
                System.out.printf("    %-24s %+d   (A=%d B=%d)%n", k, d, a.getOrDefault(k, 0), b2.getOrDefault(k, 0));
            }
        }
    }

    private static Network single() {
        Network n = VoltageControlNetworkFactory.createNetworkWithT2wt();
        TwoWindingsTransformer t = n.getTwoWindingsTransformer("T2wT");
        t.getRatioTapChanger().setTargetDeadband(0).setRegulating(true).setTapPosition(0)
                .setRegulationTerminal(t.getTerminal2()).setTargetV(34.0);
        return n;
    }

    private static Network shared() {
        Network n = VoltageControlNetworkFactory.createNetworkWith2T2wt();
        for (String id : List.of("T2wT1", "T2wT2")) {
            TwoWindingsTransformer t = n.getTwoWindingsTransformer(id);
            if (t != null && t.getRatioTapChanger() != null) {
                t.getRatioTapChanger().setTargetDeadband(0).setRegulating(true).setTapPosition(0)
                        .setRegulationTerminal(t.getTerminal2()).setTargetV(34.0);
            }
        }
        return n;
    }

    @Test
    void probeAllModes() {
        for (var mode : OpenLoadFlowParameters.TransformerVoltageControlMode.values()) {
            probe("SINGLE controller", single(), mode);
        }
        for (var mode : OpenLoadFlowParameters.TransformerVoltageControlMode.values()) {
            probe("SHARED controllers", shared(), mode);
        }
    }
}
