# JSON Schema validation reference vectors

The five JSON files are byte-for-byte copies from the JSON Schema Test Suite at commit
`e2a68a15cd490750fd6b95e604e4054d7d978d1f`. The upstream root license is included in
this directory. [Source files](https://github.com/json-schema-org/JSON-Schema-Test-Suite/tree/e2a68a15cd490750fd6b95e604e4054d7d978d1f/validation/tests)
· [Upstream license](https://github.com/json-schema-org/JSON-Schema-Test-Suite/blob/e2a68a15cd490750fd6b95e604e4054d7d978d1f/LICENSE)

`V03SchemaReferenceVectorsTest` runs each applicable instance as a separate JUnit test during
`mvn verify`. The test reads local fixtures, checks their SHA-256 digests and verifies the
positive and negative counts per file. It compares the matcher outcome to the upstream
`valid` field. A rejected schema fails the test even when the instance is expected to be invalid.

The selected groups use draft 2020-12 semantics and only the five supported keywords,
including in nested schemas. Boolean subschemas and array-form `items` fall outside the DSL.
The test lists the selected zero-based group indices explicitly so a change in the matcher
cannot silently remove failing vectors. Review the indices, digests and counts together
when updating the upstream commit. [Upstream compatibility rules](https://github.com/json-schema-org/JSON-Schema-Test-Suite/blob/e2a68a15cd490750fd6b95e604e4054d7d978d1f/validation/README.md#test-case-compatibility)

| File | Valid | Invalid | Reason for excluded groups |
| --- | ---: | ---: | --- |
| type.json | 21 | 59 | Draft-03 `any` and schema-valued type alternatives |
| enum.json | 24 | 31 | Draft-03 boolean `required` |
| items.json | 5 | 3 | Earlier draft semantics, array-form `items`, boolean schemas and additional keywords such as `prefixItems` |
| properties.json | 10 | 6 | Additional keywords and boolean property schemas |
| required.json | 12 | 6 | Draft-03 boolean `required` |
| **Total** | **72** | **105** | |
