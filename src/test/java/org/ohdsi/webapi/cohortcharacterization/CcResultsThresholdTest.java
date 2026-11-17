package org.ohdsi.webapi.cohortcharacterization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ohdsi.webapi.AbstractDatabaseTest;
import org.ohdsi.webapi.cohortcharacterization.domain.CcGenerationEntity;
import org.ohdsi.webapi.cohortcharacterization.dto.CcPrevalenceStat;
import org.ohdsi.webapi.cohortcharacterization.dto.CcResult;
import org.ohdsi.webapi.cohortcharacterization.dto.ExecutionResultRequest;
import org.ohdsi.webapi.cohortcharacterization.repository.CcGenerationEntityRepository;
import org.ohdsi.webapi.source.Source;
import org.ohdsi.webapi.source.SourceDaimon;
import org.ohdsi.webapi.source.SourceService;
import org.ohdsi.webapi.sqlrender.SourceAwareSqlRender;
import org.ohdsi.webapi.util.CancelableJdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.ConversionService;

/**
 * Reads characterization results with the prevalence threshold applied when a criteria feature has the same
 * covariate ID as a preset covariate (criteria feature IDs are small sequence numbers, and preset covariate IDs
 * such as the age group 0 - 4 covariate, 3, are small too).
 */
public class CcResultsThresholdTest extends AbstractDatabaseTest {

  private static final String CDM_SCHEMA = "cc_threshold_cdm";
  private static final String RESULTS_SCHEMA = "cc_threshold_results";
  private static final int SOURCE_ID = 9202;
  private static final long GENERATION_ID = 1;
  private static final int COHORT_ID = 5;
  private static final int AGE_GROUP_ANALYSIS_ID = 3;
  private static final int CRITERIA_ANALYSIS_ID = 15;
  private static final long SHARED_COVARIATE_ID = 3;

  @Autowired
  private ConversionService conversionService;

  private CcServiceImpl ccService;

  @Before
  public void createSchemas() {
    dropSchemas();
    jdbcTemplate.execute("CREATE SCHEMA " + CDM_SCHEMA);
    jdbcTemplate.execute("CREATE SCHEMA " + RESULTS_SCHEMA);
    jdbcTemplate.execute("CREATE TABLE " + CDM_SCHEMA + ".concept (concept_id INT, concept_name VARCHAR(255))");
    jdbcTemplate.update("INSERT INTO " + CDM_SCHEMA + ".concept VALUES (0, 'No matching concept')");
    jdbcTemplate.execute("CREATE TABLE " + RESULTS_SCHEMA + ".cc_results (type VARCHAR(255), fa_type VARCHAR(255), "
            + "cc_generation_id BIGINT, analysis_id INT, analysis_name VARCHAR(1000), covariate_id BIGINT, "
            + "covariate_name VARCHAR(1000), strata_id BIGINT, strata_name VARCHAR(1000), time_window VARCHAR(255), "
            + "concept_id INT, count_value BIGINT, avg_value DOUBLE PRECISION, stdev_value DOUBLE PRECISION, "
            + "min_value DOUBLE PRECISION, p10_value DOUBLE PRECISION, p25_value DOUBLE PRECISION, "
            + "median_value DOUBLE PRECISION, p75_value DOUBLE PRECISION, p90_value DOUBLE PRECISION, "
            + "max_value DOUBLE PRECISION, cohort_definition_id BIGINT, aggregate_id INT, "
            + "aggregate_name VARCHAR(1000), missing_means_zero INT)");

    Source source = new Source();
    source.setSourceId(SOURCE_ID);
    source.setSourceKey("ccthreshold");
    source.setSourceDialect("postgresql");
    source.setDaimons(Arrays.asList(daimon(source, SourceDaimon.DaimonType.CDM, CDM_SCHEMA),
            daimon(source, SourceDaimon.DaimonType.Results, RESULTS_SCHEMA)));

    CcGenerationEntity generation = mock(CcGenerationEntity.class);
    when(generation.getSource()).thenReturn(source);
    CcGenerationEntityRepository generationRepository = mock(CcGenerationEntityRepository.class);
    when(generationRepository.findById(GENERATION_ID)).thenReturn(Optional.of(generation));
    SourceService sourceService = mock(SourceService.class);
    when(sourceService.findBySourceId(SOURCE_ID)).thenReturn(source);

    ccService = spy(new CcServiceImpl(null, null, null, null, null, generationRepository, null,
            conversionService, null, null, null, sourceService, null, new SourceAwareSqlRender(sourceService), null,
            null, null, null, null, null, null, null, null, null));
    doReturn(new CancelableJdbcTemplate(getDataSource())).when(ccService).getSourceJdbcTemplate(source);
  }

  @After
  public void dropSchemas() {
    jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + CDM_SCHEMA + " CASCADE");
    jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + RESULTS_SCHEMA + " CASCADE");
  }

  private static SourceDaimon daimon(Source source, SourceDaimon.DaimonType type, String qualifier) {
    SourceDaimon daimon = new SourceDaimon(source);
    daimon.setDaimonType(type);
    daimon.setTableQualifier(qualifier);
    return daimon;
  }

  private void insertPrevalence(String faType, int analysisId, String analysisName, long covariateId,
          String covariateName, long count, double avg) {
    jdbcTemplate.update("INSERT INTO " + RESULTS_SCHEMA + ".cc_results (type, fa_type, cc_generation_id, "
            + "analysis_id, analysis_name, covariate_id, covariate_name, strata_id, strata_name, concept_id, "
            + "count_value, avg_value, cohort_definition_id) VALUES ('PREVALENCE', ?, ?, ?, ?, ?, ?, 0, "
            + "'All stratas', 0, ?, ?, ?)", faType, GENERATION_ID, analysisId, analysisName, covariateId,
            covariateName, count, avg, COHORT_ID);
  }

  private void insertAgeGroups(double ageGroupZeroToFourPrevalence) {
    insertPrevalence("PRESET", AGE_GROUP_ANALYSIS_ID, "DemographicsAgeGroup", SHARED_COVARIATE_ID,
            "age group:   0 -   4", Math.round(ageGroupZeroToFourPrevalence * 1000), ageGroupZeroToFourPrevalence);
    insertPrevalence("PRESET", AGE_GROUP_ANALYSIS_ID, "DemographicsAgeGroup", 13003,
            "age group:  65 -  69", 300, 0.3);
    insertPrevalence("CRITERIA_SET", CRITERIA_ANALYSIS_ID, "Prior insulin", SHARED_COVARIATE_ID,
            "Insulin in prior year", 250, 0.25);
  }

  private List<CcResult> findResults() {
    ExecutionResultRequest params = new ExecutionResultRequest();
    params.setCohortIds(Arrays.asList(COHORT_ID));
    params.setAnalysisIds(Arrays.asList(AGE_GROUP_ANALYSIS_ID, CRITERIA_ANALYSIS_ID));
    params.setThresholdValuePct(0.01f);
    return ccService.findResults(GENERATION_ID, params);
  }

  @Test
  public void presetCovariateBelowThresholdIsHiddenWhenACriteriaFeatureSharesItsId() {
    insertAgeGroups(0.001);

    assertThat(findResults())
            .extracting(CcResult::getFaType, CcResult::getAnalysisId, r -> ((CcPrevalenceStat) r).getCovariateId())
            .containsExactlyInAnyOrder(
                    tuple("PRESET", AGE_GROUP_ANALYSIS_ID, 13003L),
                    tuple("CRITERIA_SET", CRITERIA_ANALYSIS_ID, SHARED_COVARIATE_ID));
  }

  @Test
  public void covariatesSharingAnIdAcrossFeatureTypesAreReturnedOnce() {
    insertAgeGroups(0.2);

    assertThat(findResults())
            .extracting(CcResult::getFaType, CcResult::getAnalysisId, r -> ((CcPrevalenceStat) r).getCovariateId())
            .containsExactlyInAnyOrder(
                    tuple("PRESET", AGE_GROUP_ANALYSIS_ID, SHARED_COVARIATE_ID),
                    tuple("PRESET", AGE_GROUP_ANALYSIS_ID, 13003L),
                    tuple("CRITERIA_SET", CRITERIA_ANALYSIS_ID, SHARED_COVARIATE_ID));
  }
}
