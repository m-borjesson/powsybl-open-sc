# Incremental transformer zero-sequence import

This importer increment uses the existing core Fortescue APIs. It does not
implement complete finite winding shunts or the new transformer circuit.

## Source shunt conversion

Read `g0` and `b0` together on their source end voltage base. For a finite,
nonzero `Y0 = g0 + j*b0`, convert to `Zm = 1/Y0` in ohms:

```
rm = g0 / (g0*g0 + b0*b0)
xm = -b0 / (g0*g0 + b0*b0)
```

The implementation scales both components before computing the reciprocal to
avoid unnecessary squared-magnitude overflow and underflow. Nonfinite source
values and reciprocals outside the representable range are rejected with a
transformer/end diagnostic. Referral to another voltage base is a separate step:
`Znew = Zsource * (Unew/Usource)^2`.

Import records distinguish three states:

| Input | State | Finite rm/xm |
|---|---|---|
| Either component absent | MISSING | Absent |
| Both components explicitly zero | OPEN | Absent |
| Both components finite, at least one nonzero | FINITE | Converted impedance |

An OPEN branch is not `rm=xm=0`; the latter would mean an ideal short circuit.
MISSING includes incomplete pairs and retains legacy behavior; it does not
establish an open magnetizing branch.

## Existing-core subset

For 2W, set the shared `freeFluxes` flag only after both numbered source ends
have explicit OPEN shunts. For 3W, an OPEN shunt sets only that end's leg flag.
Other states leave the existing flag unchanged, including its legacy default;
this is a compatibility fallback, not evidence that an absent or finite source
shunt physically represents free fluxes.

Converted finite shunts remain transient and are not yet mapped to core or the
solver. The importer warns for each affected end. It does not aggregate distinct
source shunts into a legacy midpoint branch or introduce raw g0/b0 core fields.
Full support requires the staged per-end/per-leg storage and validated circuit
placement; do not interpret a successful legacy import as lossless finite-shunt
support. Existing neutral impedance scaling is unchanged by this increment.

## Circuit contract still to validate

The published [CGMES Short Circuit PowerTransformerEnd model](https://modellen.netbeheernederland.nl/cgmes/Short%20Circuit/PowerTransformerEnd/)
describes zero-sequence magnetizing quantities and a per-end star equivalent.
It permits distributing three-terminal no-load contributions among ends. This
does not establish that a legacy midpoint shunt is interchangeable with any
proposed source-end circuit. Validate placement, bases and taps before enabling
finite shunts in the new physical builders. Ordinary, auto and zigzag topology
validation and the independent oracle remain separate implementation gates.
