# 09. Traceability Matrix

This matrix separates framework v0.2 acceptance criteria from Phase 2 Agent Skill support evidence. Framework AC numbers in `docs/03-acceptance/04_acceptance_criteria.md` validate the reusable execution framework only; downstream Product/RP AC remain source artifacts carried through `source_refs`.

## Framework v0.2 AC Traceability

| Requirement | Feature | Acceptance Criteria | Test Case | Evidence |
|---|---|---|---|---|
| REQ-FWK-001 DSL contract validation | F007 / F011 | AC-001 | FWK-001 / FWK-008 / FWK-009 | EVD-000 |
| REQ-FWK-002 Execution Profile, Provider Instance, Provider Contract, Environment Binding, and runtime mode resolution | F007 | AC-002 | FWK-002 / FWK-008 / FWK-010 / FWK-013 | EVD-000 / EVD-007 |
| REQ-FWK-003 Parameterized test expansion | F007 | AC-003 | FWK-001 / FWK-008 | EVD-000 / EVD-007 |
| REQ-FWK-004 Fixture setup and cleanup | F007 | AC-004 | FWK-002 / FWK-012 / FWK-014; `DatabaseFixtureProviderTest#setupAndCleanupExecuteSqlRefsAndWriteFixtureEvidence` | EVD-000 / EVD-007 / EVD-009 |
| REQ-FWK-005 CLI target execution | F007 / F011 | AC-005 | FWK-002 / FWK-010 / FWK-014 | EVD-000 / EVD-009 |
| REQ-FWK-006 HTTP/gRPC target execution | F007 / F011 | AC-006 | FWK-006 / FWK-010 / FWK-014; `RequestResponseProviderTest#executesMockRestEndpointUsingContractFallbackAndNonSuccessStatus`; `RequestResponseProviderTest#writesFailedGrpcEvidenceWhenNativeInvokerReportsNonTimeoutException`; `RequestResponseProviderTest#joinsEndpointWithTrailingSlashAndActionPathWithoutLeadingSlash`; `RequestResponseProviderTest#treatsNonMappingActionsAsEmptyDefaultRequest`; `RequestResponseProviderTest#ignoresNonMappingActionValueAndFallsBackToDefaultRequest` | EVD-009 |
| REQ-FWK-007 JDBC query execution | F007 / F011 | AC-007 | FWK-006 / FWK-010 / FWK-014 | EVD-009 |
| REQ-FWK-008 Kafka/NATS event observation and NATS request/reply provider mode | F007 / F011 | AC-008 | FWK-006 / FWK-010 / FWK-014; `MessagingProviderTest#requestsMessageThroughNativeTransportWithPayloadAndFallbackTimeout`; `MessagingProviderTest#usesEmptyActionWhenConfiguredActionValueIsNotAMap`; `MessagingProviderTest#usesEmptyActionWhenNamedActionValueIsNotAMap`; `MessagingProviderTest#usesEmptyActionWhenActionMapIsEmpty`; `MessagingProviderTest#allowsObservationWithoutTargetRefAndOmitsTargetEvidenceField`; `MessagingProviderTest#convertsTransportFailureWithoutMessageIntoDefaultFailedMessagingEvidence`; `RegressionCommandTest#runDryRunAllowsMessagingCleanupWhenMaxCountIsStringPositiveInteger`; `RegressionCommandTest#runDryRunAllowsNativeMessagingRequestReplyHyphenModeAlias` | EVD-009 |
| REQ-FWK-009 File verification | F007 / F011 | AC-009 | FWK-002 / FWK-011 | EVD-000 / EVD-009 |
| REQ-FWK-010 DB polling verification | F007 / F011 | AC-010 | FWK-006 / FWK-011 | EVD-009 |
| REQ-FWK-011 Event polling verification | F007 / F011 | AC-011 | FWK-006 / FWK-011 | EVD-009 |
| REQ-FWK-012 Evidence collection | F007 / F008 | AC-012 | FWK-002 / FWK-008 / FWK-012 / FWK-014; `EvidenceWriterTest#simpleExecutionRunOverloadWritesEmptyResolutionSections`; `EvidenceWriterTest#executionRunWritesV02OutputMapFallbackWhenOutputRefIsAbsent`; `CoverageReportServiceTest#batchReportWithNoAutomatableAcceptanceCriteriaIsNotReviewReady`; `CoverageReportServiceTest#batchReportFlagsManualOrWaivedExclusionsMissingApprovalFields`; `CoverageReportServiceTest#batchReportCountsPartialAcceptanceCriteriaWhenApprovedRunPasses` | EVD-000 / EVD-007 / EVD-008 |
| REQ-FWK-013 Standard result JSON | F007 / F008 | AC-013 | FWK-002 / FWK-008 / FWK-012 / FWK-014; `EvidenceWriterTest#executionRunWritesV02OutputMapFallbackWhenOutputRefIsAbsent`; `CoverageReportServiceTest#batchReportWithNoAutomatableAcceptanceCriteriaIsNotReviewReady`; `CoverageReportServiceTest#batchReportCountsPartialAcceptanceCriteriaWhenApprovedRunPasses` | EVD-000 / EVD-007 / EVD-008 |
| REQ-FWK-014 Secret guardrails | F007 / F011 | AC-014 | FWK-001 / FWK-008 / FWK-012 | EVD-000 / EVD-009 |
| REQ-FWK-015 Test and suite selection | F007 / F008 | AC-015 | FWK-002 / FWK-008 / FWK-013 | EVD-000 / EVD-007 |
| REQ-FWK-016 Provider Contract, Provider Instance, Environment Binding, runtime mode, and verify plugin contracts | F011 | AC-016 | FWK-006 / FWK-010 / FWK-011 / FWK-013 / FWK-014 | EVD-009 |
| REQ-FWK-017 Metadata-only Product/RP/RU labels | Cross-cutting | AC-017 | FWK-003 / FWK-008 / FWK-009 | EVD-000 / EVD-009 |
| REQ-FWK-018 Profile-portable execution | F007 | AC-018 | FWK-002 / FWK-008 | EVD-000 / EVD-007 |
| REQ-FWK-019 Framework public interface | F007 / F008 / F011 | AC-001, AC-002, AC-012, AC-013, AC-015, AC-016 | FWK-005 / FWK-008 / FWK-009 / FWK-010 / FWK-013; `RegressionCommandTest#runDryRunAllowsMessagingCleanupWhenMaxCountIsStringPositiveInteger`; `RegressionCommandTest#runDryRunAllowsNativeMessagingRequestReplyHyphenModeAlias` | EVD-000 / EVD-009 |
| REQ-FWK-020 Track B Golden E2E lifecycle | F007 / F008 / F011 | AC-001, AC-002, AC-004, AC-005, AC-009, AC-012, AC-013, AC-014, AC-015, AC-016, AC-017 | FWK-015 / `GoldenE2EIT` / `SampleFakeProviderTest` / `GoldenSuiteLoaderTest` | EVD-010 |
| REQ-FWK-021 Track C P0 provider capability runtime | F007 / F008 / F011 | AC-004, AC-006, AC-007, AC-008, AC-009, AC-010, AC-011, AC-012, AC-013, AC-014, AC-015, AC-016 | FWK-016 / `ProviderCapabilityIT` / `WireMockProviderTest` / `JdbcProviderTest` / `NatsProviderTest` / `CompareEngineTest` / `PollingEngineTest` | EVD-011 |

## Phase 2 Agent Skill Support Traceability

These rows are not accepted by framework AC numbers. They are verified through support AC, readiness, generation, or review evidence because they support Product Repo/RP preparation rather than reusable runtime behavior.
They are next-stage support traceability and are not part of the current-stage framework maturity score.

| Requirement | Feature | Support AC | Support Case | Evidence |
|---|---|---|---|---|
| REQ-SUP-001 Product Repo bootstrap, RU repo discovery, draft docs/spec creation, and readiness agent skill | F001 | SUP-AC-001 | REG-RP-001 / SUP-IT-001 | EVD-001 |
| REQ-SUP-002 RP creation completeness and scaffold agent skill | F002 | SUP-AC-002 | REG-RP-002 / SUP-IT-002 | EVD-002 |
| REQ-SUP-003 RP feature spec and AC intake, including draft/proposed spec review state | F003 | SUP-AC-003 | REG-RP-003 / SUP-IT-003 | EVD-003 |
| REQ-SUP-004 Product mapping translation and generated artifact completeness | F004 | SUP-AC-004 | REG-RP-004 / SUP-IT-004 | EVD-004 |
| REQ-SUP-005 AC readiness and test drafting | F005 | SUP-AC-005 | REG-RP-005 / REG-RP-006 / SUP-IT-005 | EVD-005 |
| REQ-SUP-006 Expected-result drafting and approval | F006 | SUP-AC-006 | REG-RP-007 / SUP-IT-006 | EVD-006 |

## Downstream RP Evidence Traceability

| Requirement | Feature | Framework AC Use | RP Evidence Case | Evidence |
|---|---|---|---|---|
| REQ-RP-001 Release Package execution | F007 | Uses AC-001 through AC-018 to validate framework runtime behavior; downstream RP AC are read from `source_refs.acceptance_criteria` | REG-RP-008 / REG-RP-011 / REG-RP-012 / REG-RP-013 / REG-RP-015 / REG-RP-016 | EVD-007 |
| REQ-RP-002 Coverage and evidence package | F008 | Uses AC-012, AC-013, AC-015, and AC-017 for framework output shape; downstream coverage denominator comes from RP AC inventory | REG-RP-009 / REG-RP-010 | EVD-008 |

Every generated test case and evidence item shall trace to `source_refs.acceptance_criteria`, optional Product/RP labels, batch ID, run ID, and test case ID. Optional labels are reporting metadata only and must not drive framework runtime decisions.
