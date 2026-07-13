# Emitted ownership inspection

The optimized `stringsWork` loop is inlined into `kfun:#main(kotlin.Array<kotlin.String>)`. Inspection used deterministic `objdump -d -C` output from the preserved benchmark binaries.

| Snapshot | Candidate | Binary SHA-256 | Disassembly SHA-256 | Append result slots |
|---|---|---|---|---|
| Wave 15 | `aa4186d1e7a5856a763db0d28ec19d3359664e80` | `c10116e58d5a2ecb7edb25f8137754944b1cd5f88aa4905e18b21e473a4a37b2` | `4e6ae5bbd0449a1fc9baadde15da395915daab4706345a5e0f37591398567c55` | `rsp+0x28`, `rsp+0x30`, `rsp+0x38`, `rsp+0x48` |
| Wave 17 | `4022aee98e659d711530dc554cce283da6a47c09` | `a1e0f4656a83d366a5a7efb32574de367a8af10b13869039dac74f908de40cf6` | `b9cddba67424448f0dde5c54be404f1637f1a52251e34be20260a9726a891ce9` | `rbp` for all four calls |

Wave 15 emitted four independent result-slot addresses:

- `0x427f8e` used `rsp+0x28`.
- `0x427fa4` used `rsp+0x30`.
- `0x427fb8` used `rsp+0x38`.
- `0x4281c8` used `rsp+0x48`.

Wave 17 loads the same `rbp` slot into `rdx` immediately before the calls at `0x427119`, `0x42712d`, `0x42713f`, and `0x427351`.

The structured evidence is in [emitted-inspection.json](emitted-inspection.json). Benchmark and test provenance is in [verification.json](verification.json).
