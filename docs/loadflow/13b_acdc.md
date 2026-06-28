(ch:acdc)=

# AC--DC load flow with detailed DC grids
Besides the AC-emulation HVDC link of §{ref}`sec:hvdc` --- a single branch whose power follows a droop --- OLF can also model a *detailed* DC grid: real DC buses, DC lines and voltage-source converters solved *together* with the AC network. There is no separate DC solver here: the DC unknowns and equations are appended to the *same* AC Newton system of {ref}`part:ac`, so everything from Chapter {ref}`ch:nr` onwards (the Jacobian, `solveTransposed`, the outer loops) applies unchanged [`ac/equations/AcEquationSystemCreator.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AcEquationSystemCreator.java).

## Scope and topology

By default there is no hard limit on the number of synchronous components or DC components within a single connected component; specific limitations apply only when the [`acDcNetwork` load-flow parameter](parameters.md#acdcnetwork) is used. OLF groups converters by the connected component of their DC buses and builds one combined AC--DC subsystem per connected component `network/impl/LfNetworkLoaderImpl.java (createAcDcConverters)`. Two restrictions are always enforced at load time, each by an exception:

- **Line-commutated converters are not supported** in the detailed model --- only voltage-source converters (VSC) are built;

- **each DC network must be grounded**: at least one DC bus must carry a `DcGround`, pinning its potential, otherwise the model is rejected.

**Support across analyses.** The detailed AC--DC model is supported in **security
analysis** only when the DC components are *embedded* (one synchronous component per
connected component) and the load flow runs in **AC** mode; contingencies on DC
elements are not supported. It is **not supported in sensitivity analysis**.

## The combined unknown vector

On top of the AC unknowns $(\bm V,\bm\varphi)$ the system gains, per [`ac/equations/AcVariableType.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AcVariableType.java):

::: center
| New variable | symbol            | per       |
|:-------------|:------------------|:----------|
| `DC_BUS_V`   | $V^{\mathrm{dc}}$ | DC bus    |
| `CONV_P_AC`  | $P_{\mathrm{AC}}$ | converter |
| `CONV_Q_AC`  | $Q_{\mathrm{AC}}$ | converter |
:::

$P_{\mathrm{AC}}$ (resp. $Q_{\mathrm{AC}}$) is the active (resp. reactive) power the AC network injects *into* the converter ($P_{\mathrm{AC}}>0$ when power flows AC$\to$DC). The converter DC current is *not* a state variable: it is a derived quantity $I_{\mathrm{conv}}$ obtained from $P_{\mathrm{AC}}$ and the DC bus voltages inside the converter equation term (§{ref}`sec:acdcconv`).

## DC bus equations

Each DC bus contributes one equation `ac/equations/AcEquationSystemCreator.java (createDcBusEquation)`:

- a *grounded* bus carries `DC_BUS_GROUND`, pinning its voltage, $V^{\mathrm{dc}}=0$ (the second pole, $\mathrm{dcBus2}$, is the neutral reference used for initialisation);

- every other bus carries a current balance `DC_BUS_TARGET_I`, 

$$
\sum_i I_i = 0 ,
$$

 the $I_i$ being the currents the incident DC lines and converters push out of the bus. A grounded bus has no current-balance row (its voltage is fixed instead).

## DC lines

A DC line of resistance $R$ between buses $1$ and $2$ adds a Ohm's-law current term to each end's balance, with opposite signs [`ac/equations/dcnetwork/ClosedDcLineSide1CurrentEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/dcnetwork/ClosedDcLineSide1CurrentEquationTerm.java): 

$$
\text{at bus 1:}\ \ \sum_i I_i+\frac{V^{\mathrm{dc}}_1-V^{\mathrm{dc}}_2}{R}=0,
  \qquad
  \text{at bus 2:}\ \ \sum_i I_i-\frac{V^{\mathrm{dc}}_1-V^{\mathrm{dc}}_2}{R}=0 ,
$$

 ($R$ is per-unitised on the DC bus bases). A grounded end simply omits its term.

(sec:acdcconv)=

## Voltage-source converters
A VSC links *one* AC bus to *two* DC buses (converters with a second AC terminal are not supported). It exposes two orthogonal control choices, each adding one equation `ac/equations/AcEquationSystemCreator.java (createVoltageSourceConverterEquations)`:

::: center
| Choice | mode | equation imposed |
|:---|:---|:---|
| active/DC | `P_PCC` | `AC_CONV_TARGET_P_REF`: $P_{\mathrm{AC}}=P_{\mathrm{ref}}$ |
|  | `V_DC` | `DC_BUS_TARGET_V_REF`: $V^{\mathrm{dc}}_1-V^{\mathrm{dc}}_2=V_{\mathrm{ref}}$ |
| reactive/AC | reactive set-point | `AC_CONV_TARGET_Q_REF`: $Q_{\mathrm{AC}}=Q_{\mathrm{ref}}$ |
|  | voltage regulator | `BUS_TARGET_V`: $V_{\mathrm{AC}}=V_{\mathrm{ref}}$ |
:::

At least *one* converter of each DC grid must be in `V_DC` mode (it sets the DC voltage level), else the system is singular and OLF throws. The AC-side injection $(P_{\mathrm{AC}},Q_{\mathrm{AC}})$ enters the converter AC bus's power balance like any other injection; on the DC side the derived current $I_{\mathrm{conv}}$ (from $\mathrm{dcBus1}$ to $\mathrm{dcBus2}$) enters the two DC current balances with opposite signs, 

$$
\text{at dcBus1:}\ \ \sum_i I_i+I_{\mathrm{conv}}=0,
  \qquad
  \text{at dcBus2:}\ \ \sum_i I_i-I_{\mathrm{conv}}=0 .
$$

## Converter power balance and losses

The equation that closes the converter is the conservation of power between its AC and DC sides [`ac/equations/dcnetwork/ConverterDcCurrentEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/dcnetwork/ConverterDcCurrentEquationTerm.java): 

$$
P_{\mathrm{DC}}+P_{\mathrm{AC}}=P_{\mathrm{loss}},\qquad
  P_{\mathrm{DC}}=I_{\mathrm{conv}}\,(V^{\mathrm{dc}}_1-V^{\mathrm{dc}}_2),
$$ (eq:convbalance)

 with a current-dependent loss 

$$
P_{\mathrm{loss}}=\text{IdleLoss}+\text{SwitchingLoss}\cdot\abs{I_{\mathrm{conv}}}
                    +\text{ResistiveLoss}\cdot I_{\mathrm{conv}}^{2}\ \ge 0 .
$$ (eq:convloss)

 As rectifier ($P_{\mathrm{AC}}>0$, $P_{\mathrm{DC}}<0$) the DC side receives $\abs{P_{\mathrm{DC}}}=\abs{P_{\mathrm{AC}}}-P_{\mathrm{loss}}$; as inverter the roles swap, $\abs{P_{\mathrm{AC}}}=\abs{P_{\mathrm{DC}}}-P_{\mathrm{loss}}$ --- in both directions power is lost crossing the converter. Substituting {eq}`eq:convloss` into {eq}`eq:convbalance` gives the implicit relation OLF solves for $I_{\mathrm{conv}}$, 

$$
I_{\mathrm{conv}}(V^{\mathrm{dc}}_1-V^{\mathrm{dc}}_2)+P_{\mathrm{AC}}
   =\text{IdleLoss}+\text{SwitchingLoss}\,\abs{I_{\mathrm{conv}}}
    +\text{ResistiveLoss}\,I_{\mathrm{conv}}^{2} ,
$$

 closed in the equation term by fixing the sign $\operatorname{sign}(I_{\mathrm{conv}})=-\operatorname{sign}(P_{\mathrm{AC}})\operatorname{sign}(V^{\mathrm{dc}}_1-V^{\mathrm{dc}}_2)$ and solving the resulting linear (no resistive loss) or quadratic equation in closed form. The three loss coefficients are per-unitised from the converter's configured loss factors.

:::{admonition} Remark - One Newton system, two physics
:class: seealso
Nothing above introduces a second solver: the DC bus voltages, converter powers and the derived $I_{\mathrm{conv}}$ are extra rows and columns in the *same* Jacobian the AC load flow already factorises. The detailed DC grid is, from the solver's point of view, just more equation terms --- the same design that lets zero-impedance branches (§{ref}`sec:zeroimp`) and HVDC AC emulation (§{ref}`sec:hvdc`) live in one system.

:::
