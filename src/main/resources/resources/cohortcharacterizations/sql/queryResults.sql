-- If results may be shown in comparison mode (there are several records for the same covariate ID in report),
-- filter out only those covariates where all siblings are below threshold.
-- Covariate IDs are only unique within a feature type: a criteria feature ID or a custom feature covariate ID
-- can equal a preset covariate ID, so passed IDs are matched on type and fa_type as well.
WITH threshold_passed_ids AS (
  select r.type, r.fa_type, r.covariate_id
  from @results_database_schema.cc_results r
  where r.cc_generation_id = @cohort_characterization_generation_id
  GROUP BY r.type, r.fa_type, covariate_id
  HAVING (r.fa_type <> 'PRESET' or r.type <> 'PREVALENCE' OR MAX(avg_value) > @threshold_level)
)
select
       r.type,
       r.fa_type,
       r.cc_generation_id,
       r.analysis_id,
       r.analysis_name,
       r.covariate_id,
       r.covariate_name,
       c.concept_name,
       r.time_window,
       r.concept_id,
       r.count_value,
       r.avg_value,
       r.stdev_value,
       r.min_value,
       r.p10_value,
       r.p25_value,
       r.median_value,
       r.p75_value,
       r.p90_value,
       r.max_value,
       r.cohort_definition_id,
       r.strata_id,
       r.strata_name,
       r.aggregate_id,
       r.aggregate_name,
       r.missing_means_zero
from @results_database_schema.cc_results r
  JOIN threshold_passed_ids tpi ON tpi.type = r.type
    AND tpi.fa_type = r.fa_type
    AND tpi.covariate_id = r.covariate_id
  JOIN @vocabulary_schema.concept c on c.concept_id = r.concept_id
where r.cc_generation_id = @cohort_characterization_generation_id
  and r.analysis_id in (@analysis_ids)
  and r.cohort_definition_id in (@cohort_ids)
