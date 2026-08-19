package com.powsybl.openloadflow.sensi;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControlAdder;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Is the SVC-pilot finite difference limited by TRUNCATION (error ~ dV^2, shrinks with a smaller step) or by
 * the load flow's own CONVERGENCE tolerance (error ~ 1/dV, shrinks with a LARGER step)? The two have opposite
 * remedies, so the failing assertion cannot be fixed without knowing which.
 */
class SvcFdStepProbe {

    private static Network ieee14WithZone(double pilotTargetV) {
        Network network = IeeeCdfNetworkFactory.create14();
        network.getGenerator("B8-G").newMinMaxReactiveLimits().setMinQ(-6).setMaxQ(200).add();
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone().withName("z1")
                    .newPilotPoint().withTargetV(pilotTargetV).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                    .newControlUnit().withId("B6-G").add()
                    .newControlUnit().withId("B8-G").add()
                    .add()
                .add();
        return network;
    }

    private static LoadFlowParameters params() {
        LoadFlowParameters lfp = new LoadFlowParameters().setUseReactiveLimits(false);
        OpenLoadFlowParameters.create(lfp)
                .setSecondaryVoltageControl(true)
                .setMaxPlausibleTargetVoltage(1.6)
                .setNetworkCacheEnabled(true);
        return lfp;
    }

    @Test
    void probe() {
        double targetV = 13.0;
        // Reference values from the forward closed-loop sensitivity: B10 is exactly 1 (a pilot tracks its own
        // target, pinned by AcSvcPilotPointSensitivityTest#testSvcClosedLoopSensitivityIeee14), B6 is
        // 0.8088301958. The point of the sweep is that the FD, not the sensitivity, is the loose side.
        List<String> buses = List.of("B10", "B6", "B4");
        System.out.printf("%n  %-8s %-22s %-22s %-22s%n", "step", "B10", "B6", "B4");
        for (double dv : new double[] {0.4, 0.2, 0.1, 0.05, 0.025, 0.0125, 0.00625}) {
            Network before = ieee14WithZone(targetV - dv);
            Network after = ieee14WithZone(targetV + dv);
            LoadFlow.find("OpenLoadFlow").run(before, params());
            LoadFlow.find("OpenLoadFlow").run(after, params());
            StringBuilder row = new StringBuilder(String.format("  %-8.5f", dv));
            for (String bus : buses) {
                double fd = (after.getBusBreakerView().getBus(bus).getV()
                        - before.getBusBreakerView().getBus(bus).getV()) / (2 * dv);
                row.append(String.format(" %-22.16f", fd));
            }
            System.out.println(row);
        }
        System.out.println("  closed-loop S:  1.0000000000000000     0.8088301958");
    }
}
