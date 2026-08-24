# Miit Band 8+ Binding Plan

## Goal
Implement reliable Xiaomi Smart Band 8/9/10 pairing and app-level binding for China and global/regional variants.

## Current diagnostic finding
The Band 9 test showed two stages:
1. Android BLE bonding succeeds.
2. The band starts a Xiaomi-specific app-level binding/authentication stage after GATT service discovery.

Miit currently completes stage 1 and GATT discovery, but does not yet complete stage 2.

## Implementation plan
1. Detect the exact model, firmware and exposed GATT services/characteristics.
2. Build a Band 8+ protocol adapter rather than using the legacy FEE1 authentication flow.
3. Reproduce the required Xiaomi binding/authentication message sequence from Gadgetbridge and verified Band 9 protocol research.
4. Handle the user confirmation/binding callback and wait for the authenticated/bound state.
5. Persist only the minimum non-secret device binding state required by Miit.
6. Add detailed diagnostic logging for every binding stage, while never logging authentication secrets.
7. Verify the flow on the user's Band 9 before extending the adapter to Band 8 and Band 10 variants.
8. Keep model/firmware capability detection separate so regional variants can use the appropriate protocol implementation.

## Success condition
A user can select a Band 8/9/10, confirm the request on the band, and Miit reaches an authenticated/bound state without requiring Mi Fitness to complete the second pairing round.
