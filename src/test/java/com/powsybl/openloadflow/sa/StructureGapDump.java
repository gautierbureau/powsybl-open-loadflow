/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.json.JsonLoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrix;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.solver.AcSolverUtil;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.network.GeneratorVoltageControl;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LoadFlowModel;
import com.powsybl.openloadflow.network.VoltageControl;
import com.powsybl.openloadflow.network.impl.Networks;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * THROWAWAY measurement tool (phase 0 of the fixed-structure plan, see the olf-gpu repository,
 * docs/design/fixed-structure-equation-system.tex). Dumps, for one network, the Jacobian sparsity pattern of the
 * legacy modeling and of the alternative equations modeling with every row and column named by network element id,
 * so the patterns can be compared outside of OLF with the GPU superset pattern (olf-gpu tools/structure-gap).
 *
 * <p>For each modeling the pattern is dumped twice: at initialization (every voltage control on) and after a load
 * flow (some generators switched to PQ). With alternative equations both must be identical; with the legacy
 * modeling the difference is what a fixed structure saves.
 *
 * <p>Also reports which buses and voltage controls are NOT eligible to alternative equations and why (a copy of the
 * predicates of AcEquationSystemCreator, kept here because this tool is meant to be deleted), and a timing of the
 * numerical factorization on each pattern.
 *
 * <p>Usage (not a test, run its main):
 * <pre>
 * ./mvnw -q test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=com.powsybl.openloadflow.sa.StructureGapDump \
 *     -Dexec.args="ieee118 target/structure-gap [parameters.json]"
 * </pre>
 * The network is {@code ieee14}, {@code ieee57}, {@code ieee118}, {@code ieee300}, a MATPOWER Pegase case name
 * ({@code case1354pegase}, {@code case9241pegase}, ... downloaded once) or a path to a file powsybl can read.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public final class StructureGapDump {

    private static final int FACTORIZATION_RUNS = 20;

    private StructureGapDump() {
    }

    private enum Modeling {
        /** the legacy modeling: equations activated and deactivated */
        LEGACY(false, false),
        /** alternative equations as a plain load flow builds them */
        ALTERNATIVE(true, false),
        /** alternative equations as a security analysis builds them: every eligible bus can be disabled */
        ALTERNATIVE_SA(true, true);

        private final boolean alternativeEquations;
        private final boolean busesCanBeDisabled;

        Modeling(boolean alternativeEquations, boolean busesCanBeDisabled) {
            this.alternativeEquations = alternativeEquations;
            this.busesCanBeDisabled = busesCanBeDisabled;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: StructureGapDump <network> <output directory> [load flow parameters json]");
        }
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
        ((Logger) LoggerFactory.getLogger("com.powsybl")).setLevel(Level.WARN);
        String networkName = args[0];
        Path outputDir = Path.of(args[1]);
        Files.createDirectories(outputDir);
        Path parametersFile = args.length > 2 ? Path.of(args[2]) : null;

        for (Modeling modeling : Modeling.values()) {
            // a fresh network per modeling: a load flow writes its result back to the LfNetwork only, but a fresh
            // one keeps the three runs strictly independent
            Network network = loadNetwork(networkName);
            String label = Path.of(networkName).getFileName().toString().replaceAll("\\.[^.]*$", "");
            Path file = outputDir.resolve(label + "." + modeling.name().toLowerCase() + ".json");
            dump(network, label, modeling, parametersFile, file);
            System.out.println("written " + file);
        }
    }

    private static Network loadNetwork(String name) {
        return switch (name) {
            case "ieee14" -> IeeeCdfNetworkFactory.create14();
            case "ieee57" -> IeeeCdfNetworkFactory.create57();
            case "ieee118" -> IeeeCdfNetworkFactory.create118();
            case "ieee300" -> IeeeCdfNetworkFactory.create300();
            default -> name.matches("case\\d+\\w*") ? AlternativeEquationsBenchmark.loadPegaseNetwork(name) : Network.read(Path.of(name));
        };
    }

    private static void dump(Network network, String label, Modeling modeling, Path parametersFile, Path file) throws IOException {
        LoadFlowParameters parameters = parametersFile != null ? JsonLoadFlowParameters.read(parametersFile) : new LoadFlowParameters();
        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.get(parameters);
        if (parametersExt == null) {
            parametersExt = OpenLoadFlowParameters.create(parameters);
        }
        parametersExt.setAlternativeEquations(modeling.alternativeEquations);

        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, parametersExt,
                new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
        // what createAcParameters decided: the run falls back to the legacy modeling as a whole for some features
        boolean alternativeEquationsEffective = acParameters.getEquationSystemCreationParameters().isAlternativeEquations();
        acParameters.getEquationSystemCreationParameters().setAlternativeBusesCanBeDisabled(modeling.busesCanBeDisabled);

        List<LfNetwork> lfNetworks = Networks.load(network, acParameters.getNetworkParameters(), ReportNode.NO_OP);
        LfNetwork lfNetwork = lfNetworks.get(0);

        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             JsonGenerator json = new JsonFactory().createGenerator(Files.newBufferedWriter(file))) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            json.writeStringField("network", label);
            json.writeStringField("modeling", modeling.name());
            json.writeBooleanField("alternativeEquationsRequested", modeling.alternativeEquations);
            json.writeBooleanField("alternativeEquationsEffective", alternativeEquationsEffective);
            json.writeNumberField("busCount", lfNetwork.getBuses().size());
            json.writeNumberField("branchCount", lfNetwork.getBranches().size());

            writeCensus(json, lfNetwork);

            // the state the solver starts from: the derivatives need one, and the engine sets the same
            acParameters.getVoltageInitializer().prepare(lfNetwork, ReportNode.NO_OP);
            AcSolverUtil.initStateVector(lfNetwork, context.getEquationSystem(), acParameters.getVoltageInitializer());
            json.writeFieldName("initial");
            Snapshot initial = writeSnapshot(json, lfNetwork, context);

            AcLoadFlowResult result = new AcloadFlowEngine(context).run();
            json.writeStringField("loadFlowStatus", result.getSolverStatus().name());
            json.writeNumberField("loadFlowOuterLoopIterations", result.getOuterLoopIterations());
            json.writeNumberField("structureBuildCount", context.getJacobianMatrix().getStructureBuildCount());

            json.writeFieldName("solved");
            Snapshot solved = writeSnapshot(json, lfNetwork, context);

            json.writeBooleanField("patternUnchangedByLoadFlow", initial.equals(solved));
            json.writeEndObject();
        }
    }

    // ---- the pattern, named by element ----

    private record Snapshot(List<String> rows, List<String> columns, int[] columnStart, int[] rowIndices) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Snapshot other && rows.equals(other.rows) && columns.equals(other.columns)
                    && Arrays.equals(columnStart, other.columnStart) && Arrays.equals(rowIndices, other.rowIndices);
        }

        @Override
        public int hashCode() {
            return rows.hashCode();
        }
    }

    private static String elementId(LfNetwork network, ElementType elementType, int num) {
        return network.getElement(elementType, num).getId();
    }

    /**
     * OLF's matrix is the transposed Jacobian in compressed column form: one column per equation, one row per
     * variable. Read as compressed rows, the same arrays are the true Jacobian.
     */
    private static Snapshot writeSnapshot(JsonGenerator json, LfNetwork network, AcLoadFlowContext context) throws IOException {
        EquationSystem<AcVariableType, AcEquationType> equationSystem = context.getEquationSystem();
        JacobianMatrix<AcVariableType, AcEquationType> jacobian = context.getJacobianMatrix();
        jacobian.forceUpdate();
        SparseMatrix matrix = (SparseMatrix) jacobian.getMatrix();
        int n = matrix.getColumnCount();
        int[] columnStart = Arrays.copyOf(matrix.getColumnStart(), n + 1);
        int[] rowIndices = Arrays.copyOf(matrix.getRowIndices(), columnStart[n]);

        List<String> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        Map<String, Integer> activeEquationTypeCounts = new TreeMap<>();

        json.writeStartObject();
        json.writeNumberField("n", n);
        json.writeNumberField("nnz", columnStart[n]);

        // variables: [row, type, element id]
        json.writeArrayFieldStart("variables");
        for (Variable<AcVariableType> variable : equationSystem.getIndex().getSortedVariablesToFind()) {
            String id = elementId(network, variable.getType().getElementType(), variable.getElementNum());
            json.writeStartArray();
            json.writeNumber(variable.getRow());
            json.writeString(variable.getType().name());
            json.writeString(id);
            json.writeEndArray();
            rows.add(variable.getType().name() + "/" + id);
        }
        json.writeEndArray();

        // equations: [column, primary type, element id, active type, active element id]
        json.writeArrayFieldStart("equations");
        for (int column = 0; column < n; column++) {
            Equation<AcVariableType, AcEquationType> equation = equationSystem.getIndex().getEquationAtColumn(column);
            String id = elementId(network, equation.getType().getElementType(), equation.getElementNum());
            String activeId = elementId(network, equation.getActiveType().getElementType(), equation.getActiveElementNum());
            json.writeStartArray();
            json.writeNumber(column);
            json.writeString(equation.getType().name());
            json.writeString(id);
            json.writeString(equation.getActiveType().name());
            json.writeString(activeId);
            json.writeEndArray();
            // the column's identity only: which alternative is active is a value, not structure
            columns.add(equation.getType().name() + "/" + id);
            activeEquationTypeCounts.merge(equation.getActiveType().name(), 1, Integer::sum);
        }
        json.writeEndArray();

        json.writeObjectFieldStart("activeEquationTypeCounts");
        for (Map.Entry<String, Integer> e : activeEquationTypeCounts.entrySet()) {
            json.writeNumberField(e.getKey(), e.getValue());
        }
        json.writeEndObject();

        // entries: columnStart[n + 1] and rowIndices[nnz], column = equation, row = variable
        json.writeArrayFieldStart("columnStart");
        for (int v : columnStart) {
            json.writeNumber(v);
        }
        json.writeEndArray();
        json.writeArrayFieldStart("rowIndices");
        for (int v : rowIndices) {
            json.writeNumber(v);
        }
        json.writeEndArray();

        json.writeNumberField("factorizationMicroseconds", timeFactorization(matrix));
        json.writeEndObject();
        return new Snapshot(rows, columns, columnStart, rowIndices);
    }

    /** Median time of a full LU decomposition (symbolic + numeric) of the pattern with its current values. */
    private static long timeFactorization(SparseMatrix matrix) {
        long[] times = new long[FACTORIZATION_RUNS];
        for (int i = 0; i < FACTORIZATION_RUNS; i++) {
            long start = System.nanoTime();
            try (var lu = matrix.decomposeLU()) {
                times[i] = (System.nanoTime() - start) / 1000;
            }
        }
        Arrays.sort(times);
        return times[FACTORIZATION_RUNS / 2];
    }

    // ---- eligibility census (a copy of AcEquationSystemCreator's predicates, with the reasons kept apart) ----

    private static Set<String> busIneligibilityReasons(LfBus bus) {
        Set<String> reasons = new LinkedHashSet<>();
        if (!bus.getConverters().isEmpty()) {
            reasons.add("converter");
        }
        if (bus.isFictitious()) {
            reasons.add("fictitious");
        }
        if (bus.getControllerShunt().isPresent()) {
            reasons.add("controllerShunt");
        }
        if (bus.isShuntVoltageControlled()) {
            reasons.add("shuntVoltageControlled");
        }
        if (bus.isTransformerVoltageControlled()) {
            reasons.add("transformerVoltageControlled");
        }
        if (bus.getBranches().stream().anyMatch(branch -> branch.isZeroImpedance(LoadFlowModel.AC))) {
            reasons.add("zeroImpedanceBranch");
        }
        return reasons;
    }

    private static Set<String> voltageControlBusIncompatibilities(LfBus bus) {
        Set<String> reasons = new LinkedHashSet<>();
        if (bus.hasGeneratorsWithSlope()) {
            reasons.add("generatorWithSlope");
        }
        if (bus.getTransformerVoltageControl().isPresent()) {
            reasons.add("transformerVoltageControl");
        }
        if (bus.getShuntVoltageControl().isPresent()) {
            reasons.add("shuntVoltageControl");
        }
        if (bus.hasGeneratorReactivePowerControl()) {
            reasons.add("generatorReactivePowerControl");
        }
        if (!bus.getConverters().isEmpty()) {
            reasons.add("converter");
        }
        if (bus.getBranches().stream().anyMatch(branch -> branch.isZeroImpedance(LoadFlowModel.AC))) {
            reasons.add("zeroImpedanceBranch");
        }
        return reasons;
    }

    private static Set<String> voltageControlIneligibilityReasons(GeneratorVoltageControl voltageControl) {
        Set<String> reasons = new LinkedHashSet<>();
        if (voltageControl.getMergeStatus() != VoltageControl.MergeStatus.MAIN) {
            reasons.add("mergedDependent");
        }
        if (!voltageControl.getMergedDependentVoltageControls().isEmpty()) {
            reasons.add("mergedMain");
        }
        if (voltageControl.getMergedControllerElements().isEmpty()) {
            reasons.add("noController");
        }
        reasons.addAll(voltageControlBusIncompatibilities(voltageControl.getControlledBus()));
        for (LfBus controller : voltageControl.getMergedControllerElements()) {
            reasons.addAll(voltageControlBusIncompatibilities(controller));
        }
        return reasons;
    }

    private static void writeCounts(JsonGenerator json, String name, Map<String, Integer> counts) throws IOException {
        json.writeObjectFieldStart(name);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            json.writeNumberField(e.getKey(), e.getValue());
        }
        json.writeEndObject();
    }

    private static void writeCensus(JsonGenerator json, LfNetwork network) throws IOException {
        Map<String, Integer> busReasons = new LinkedHashMap<>();
        int ineligibleBuses = 0;
        List<String> ineligibleBusIds = new ArrayList<>();
        for (LfBus bus : network.getBuses()) {
            Set<String> reasons = busIneligibilityReasons(bus);
            if (!reasons.isEmpty()) {
                ineligibleBuses++;
                ineligibleBusIds.add(bus.getId());
                reasons.forEach(r -> busReasons.merge(r, 1, Integer::sum));
            }
        }

        Map<String, Integer> controlReasons = new LinkedHashMap<>();
        int controls = 0;
        int ineligibleControls = 0;
        int remoteControls = 0;
        int sharedControls = 0;
        Set<GeneratorVoltageControl> seen = new LinkedHashSet<>();
        for (LfBus bus : network.getBuses()) {
            GeneratorVoltageControl voltageControl = bus.getGeneratorVoltageControl().orElse(null);
            if (voltageControl == null || !seen.add(voltageControl)) {
                continue;
            }
            controls++;
            if (!voltageControl.isLocalControl()) {
                remoteControls++;
            }
            if (voltageControl.getMergedControllerElements().size() > 1) {
                sharedControls++;
            }
            Set<String> reasons = voltageControlIneligibilityReasons(voltageControl);
            if (!reasons.isEmpty()) {
                ineligibleControls++;
                reasons.forEach(r -> controlReasons.merge(r, 1, Integer::sum));
            }
        }

        json.writeObjectFieldStart("census");
        json.writeNumberField("buses", network.getBuses().size());
        json.writeNumberField("busesNotEligible", ineligibleBuses);
        writeCounts(json, "busesNotEligibleByReason", busReasons);
        json.writeNumberField("generatorVoltageControls", controls);
        json.writeNumberField("generatorVoltageControlsRemote", remoteControls);
        json.writeNumberField("generatorVoltageControlsShared", sharedControls);
        json.writeNumberField("generatorVoltageControlsNotEligible", ineligibleControls);
        writeCounts(json, "generatorVoltageControlsNotEligibleByReason", controlReasons);
        json.writeNumberField("zeroImpedanceBranches",
                (int) network.getBranches().stream().filter(b -> b.isZeroImpedance(LoadFlowModel.AC)).count());
        json.writeArrayFieldStart("busesNotEligibleIds");
        for (String id : ineligibleBusIds) {
            json.writeString(id);
        }
        json.writeEndArray();
        json.writeEndObject();
    }
}
