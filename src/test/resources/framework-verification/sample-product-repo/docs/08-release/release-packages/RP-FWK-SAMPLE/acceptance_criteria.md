acceptance_criteria:
  - ac_id: RP-FWK-SAMPLE-AC-001
    rp_id: RP-FWK-SAMPLE
    title: Sample adapter output matches approved expected result
    owner: platform
    classification: automatable
    input: fixtures/db/orders_seed.yaml
    behavior: run sample data pipeline adapter
    expected_output: expected/output/orders.csv
    pass_fail_rule: actual output matches approved expected output
    status: ready_for_generation
