/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.util;

import com.powsybl.iidm.network.Network;
import com.powsybl.matpower.model.MBranch;
import com.powsybl.matpower.model.MBus;
import com.powsybl.matpower.model.MGen;
import com.powsybl.matpower.model.MatpowerFormatVersion;
import com.powsybl.matpower.model.MatpowerModel;
import com.powsybl.matpower.model.MatpowerWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a MATPOWER {@code .m} case file (the MATLAB text format published by MATPOWER, e.g. the Pégase cases)
 * into a powsybl {@link Network}.
 *
 * <p>The powsybl {@code MatpowerImporter} only reads the binary {@code .mat} format, so this helper parses the
 * {@code mpc.baseMVA} / {@code mpc.bus} / {@code mpc.gen} / {@code mpc.branch} matrices from the {@code .m} file,
 * builds a {@link MatpowerModel}, writes a {@code .mat} next to it (cached), and reads it back as a {@link Network}.
 *
 * @author Claude
 */
public final class MatpowerCaseLoader {

    private static final Pattern SCALAR_BASE_MVA = Pattern.compile("mpc\\.baseMVA\\s*=\\s*([-+0-9.eE]+)\\s*;");

    private MatpowerCaseLoader() {
    }

    public static Network readDotM(Path dotMFile) {
        try {
            Path matFile = toMatFile(dotMFile);
            if (!Files.exists(matFile)) {
                MatpowerModel model = parse(dotMFile);
                MatpowerWriter.write(model, matFile, true);
            }
            return Network.read(matFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path toMatFile(Path dotMFile) {
        String name = dotMFile.getFileName().toString().replaceFirst("\\.m$", "") + ".mat";
        return dotMFile.resolveSibling(name);
    }

    static MatpowerModel parse(Path dotMFile) throws IOException {
        String content = Files.readString(dotMFile);
        String caseName = dotMFile.getFileName().toString().replaceFirst("\\.m$", "");
        MatpowerModel model = new MatpowerModel(caseName);
        model.setVersion(MatpowerFormatVersion.V2);

        Matcher m = SCALAR_BASE_MVA.matcher(content);
        model.setBaseMva(m.find() ? Double.parseDouble(m.group(1)) : 100.0);

        for (double[] row : matrix(content, "bus")) {
            model.addBus(toBus(row));
        }
        for (double[] row : matrix(content, "gen")) {
            model.addGenerator(toGen(row));
        }
        for (double[] row : matrix(content, "branch")) {
            model.addBranch(toBranch(row));
        }
        return model;
    }

    private static MBus toBus(double[] r) {
        MBus bus = new MBus();
        bus.setNumber((int) r[0]);
        bus.setName("BUS-" + (int) r[0]);
        bus.setType(MBus.Type.fromInt((int) r[1]));
        bus.setRealPowerDemand(r[2]);
        bus.setReactivePowerDemand(r[3]);
        bus.setShuntConductance(r[4]);
        bus.setShuntSusceptance(r[5]);
        bus.setAreaNumber((int) r[6]);
        bus.setVoltageMagnitude(r[7]);
        bus.setVoltageAngle(r[8]);
        bus.setBaseVoltage(r[9]);
        bus.setLossZone((int) r[10]);
        bus.setMaximumVoltageMagnitude(r[11]);
        bus.setMinimumVoltageMagnitude(r[12]);
        return bus;
    }

    private static MGen toGen(double[] r) {
        MGen gen = new MGen();
        gen.setNumber((int) r[0]);
        gen.setRealPowerOutput(r[1]);
        gen.setReactivePowerOutput(r[2]);
        gen.setMaximumReactivePowerOutput(r[3]);
        gen.setMinimumReactivePowerOutput(r[4]);
        gen.setVoltageMagnitudeSetpoint(r[5]);
        gen.setTotalMbase(r[6]);
        gen.setStatus((int) r[7]);
        gen.setMaximumRealPowerOutput(r[8]);
        gen.setMinimumRealPowerOutput(r[9]);
        return gen;
    }

    private static MBranch toBranch(double[] r) {
        MBranch branch = new MBranch();
        branch.setFrom((int) r[0]);
        branch.setTo((int) r[1]);
        branch.setR(r[2]);
        branch.setX(r[3]);
        branch.setB(r[4]);
        branch.setRateA(r[5]);
        branch.setRateB(r[6]);
        branch.setRateC(r[7]);
        branch.setRatio(r[8]);
        branch.setPhaseShiftAngle(r[9]);
        branch.setStatus((int) r[10]);
        branch.setAngMin(r.length > 11 ? r[11] : -360.0);
        branch.setAngMax(r.length > 12 ? r[12] : 360.0);
        return branch;
    }

    /**
     * Extract the numeric rows of a {@code mpc.<name> = [ ... ];} matrix block.
     */
    private static List<double[]> matrix(String content, String name) {
        int start = content.indexOf("mpc." + name);
        if (start < 0) {
            return List.of();
        }
        int open = content.indexOf('[', start);
        int close = content.indexOf("];", open);
        String body = content.substring(open + 1, close);

        List<double[]> rows = new ArrayList<>();
        for (String rawRow : body.split(";")) {
            String row = stripComment(rawRow).trim();
            if (row.isEmpty()) {
                continue;
            }
            String[] tokens = row.split("[\\s,]+");
            double[] values = new double[tokens.length];
            int n = 0;
            for (String token : tokens) {
                if (!token.isEmpty()) {
                    values[n++] = parseMatlabDouble(token);
                }
            }
            if (n > 0) {
                double[] trimmed = new double[n];
                System.arraycopy(values, 0, trimmed, 0, n);
                rows.add(trimmed);
            }
        }
        return rows;
    }

    private static double parseMatlabDouble(String token) {
        return switch (token) {
            case "Inf", "inf" -> Double.MAX_VALUE;
            case "-Inf", "-inf" -> -Double.MAX_VALUE;
            case "NaN", "nan" -> 0.0;
            default -> Double.parseDouble(token);
        };
    }

    private static String stripComment(String line) {
        int idx = line.indexOf('%');
        return idx < 0 ? line : line.substring(0, idx);
    }
}
