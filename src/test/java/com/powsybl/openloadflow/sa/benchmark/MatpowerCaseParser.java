/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa.benchmark;

import com.powsybl.matpower.model.MBranch;
import com.powsybl.matpower.model.MBus;
import com.powsybl.matpower.model.MGen;
import com.powsybl.matpower.model.MatpowerFormatVersion;
import com.powsybl.matpower.model.MatpowerModel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal parser for the MATPOWER case text format ({@code .m}). It reads the {@code baseMVA}, {@code bus},
 * {@code gen} and {@code branch} matrices into a {@link MatpowerModel} so that the official
 * {@code powsybl-matpower-converter} importer can then convert it to an IIDM network. Only the columns needed to
 * build a network for a power flow / security analysis are read; cost data and other optional sections are ignored.
 *
 * <p>This is only meant to feed benchmarks with publicly available MATPOWER cases such as the PEGASE networks
 * (e.g. {@code case13659pegase.m}). It is not a full featured MATPOWER reader.</p>
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class MatpowerCaseParser {

    private static final Pattern BASE_MVA_PATTERN = Pattern.compile("mpc\\.baseMVA\\s*=\\s*([\\d.eE+-]+)");
    private static final Pattern MATRIX_START_PATTERN = Pattern.compile("mpc\\.(bus|gen|branch)\\s*=\\s*\\[");

    private MatpowerCaseParser() {
    }

    private enum Section {
        NONE, BUS, GEN, BRANCH
    }

    public static MatpowerModel parse(Path file, String caseName) {
        MatpowerModel model = new MatpowerModel(caseName);
        model.setVersion(MatpowerFormatVersion.V2);
        model.setBaseMva(100.0);
        Section section = Section.NONE;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String content = stripComment(line).trim();
                if (content.isEmpty()) {
                    continue;
                }
                if (section == Section.NONE) {
                    Matcher baseMva = BASE_MVA_PATTERN.matcher(content);
                    if (baseMva.find()) {
                        model.setBaseMva(Double.parseDouble(baseMva.group(1)));
                        continue;
                    }
                    Matcher matrixStart = MATRIX_START_PATTERN.matcher(content);
                    if (matrixStart.find()) {
                        section = switch (matrixStart.group(1)) {
                            case "bus" -> Section.BUS;
                            case "gen" -> Section.GEN;
                            case "branch" -> Section.BRANCH;
                            default -> Section.NONE;
                        };
                    }
                    continue;
                }
                // inside a matrix
                boolean end = content.contains("]");
                String row = end ? content.substring(0, content.indexOf(']')) : content;
                row = row.replace(";", " ").trim();
                if (!row.isEmpty()) {
                    parseRow(model, section, row.split("\\s+"));
                }
                if (end) {
                    section = Section.NONE;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return model;
    }

    private static String stripComment(String line) {
        int comment = line.indexOf('%');
        return comment >= 0 ? line.substring(0, comment) : line;
    }

    private static void parseRow(MatpowerModel model, Section section, String[] columns) {
        switch (section) {
            case BUS -> model.addBus(parseBus(columns));
            case GEN -> model.addGenerator(parseGenerator(columns));
            case BRANCH -> model.addBranch(parseBranch(columns));
            default -> { /* nothing */ }
        }
    }

    private static MBus parseBus(String[] c) {
        MBus bus = new MBus();
        bus.setNumber((int) parse(c, 0));
        bus.setType(MBus.Type.fromInt((int) parse(c, 1)));
        bus.setRealPowerDemand(parse(c, 2));
        bus.setReactivePowerDemand(parse(c, 3));
        bus.setShuntConductance(parse(c, 4));
        bus.setShuntSusceptance(parse(c, 5));
        bus.setAreaNumber((int) parse(c, 6));
        bus.setVoltageMagnitude(parse(c, 7));
        bus.setVoltageAngle(parse(c, 8));
        bus.setBaseVoltage(parse(c, 9));
        bus.setLossZone((int) parse(c, 10));
        bus.setMaximumVoltageMagnitude(parse(c, 11));
        bus.setMinimumVoltageMagnitude(parse(c, 12));
        return bus;
    }

    private static MGen parseGenerator(String[] c) {
        MGen gen = new MGen();
        gen.setNumber((int) parse(c, 0));
        gen.setRealPowerOutput(parse(c, 1));
        gen.setReactivePowerOutput(parse(c, 2));
        gen.setMaximumReactivePowerOutput(parse(c, 3));
        gen.setMinimumReactivePowerOutput(parse(c, 4));
        gen.setVoltageMagnitudeSetpoint(parse(c, 5));
        gen.setTotalMbase(parse(c, 6));
        gen.setStatus((int) parse(c, 7));
        gen.setMaximumRealPowerOutput(parse(c, 8));
        gen.setMinimumRealPowerOutput(parse(c, 9));
        return gen;
    }

    private static MBranch parseBranch(String[] c) {
        MBranch branch = new MBranch();
        branch.setFrom((int) parse(c, 0));
        branch.setTo((int) parse(c, 1));
        branch.setR(parse(c, 2));
        branch.setX(parse(c, 3));
        branch.setB(parse(c, 4));
        branch.setRateA(parse(c, 5));
        branch.setRateB(parse(c, 6));
        branch.setRateC(parse(c, 7));
        branch.setRatio(parse(c, 8));
        branch.setPhaseShiftAngle(parse(c, 9));
        branch.setStatus((int) parse(c, 10));
        branch.setAngMin(parse(c, 11));
        branch.setAngMax(parse(c, 12));
        return branch;
    }

    private static double parse(String[] columns, int index) {
        return index < columns.length ? Double.parseDouble(columns[index]) : 0.0;
    }
}
