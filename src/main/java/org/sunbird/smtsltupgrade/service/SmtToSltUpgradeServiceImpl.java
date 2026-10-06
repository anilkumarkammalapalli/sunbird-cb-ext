package org.sunbird.smtsltupgrade.service;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.sunbird.common.model.SBApiResponse;
import org.sunbird.common.model.SearchUserApiContent;
import org.sunbird.common.model.SearchUserApiResp;
import org.sunbird.common.service.OutboundRequestHandlerServiceImpl;
import org.sunbird.common.util.CbExtServerProperties;
import org.sunbird.common.util.Constants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SmtToSltUpgradeServiceImpl implements SmtToSltUpgradeService {

    private static final Logger logger = LoggerFactory.getLogger(SmtToSltUpgradeServiceImpl.class);

    private final CbExtServerProperties props;

    private final OutboundRequestHandlerServiceImpl outboundRequestHandlerService;

    private final ObjectMapper objectMapper;

    private final RestHighLevelClient sbEsClient;

    public SmtToSltUpgradeServiceImpl(CbExtServerProperties props,
            OutboundRequestHandlerServiceImpl outboundRequestHandlerService, ObjectMapper objectMapper,
            @Qualifier("sbEsClient") RestHighLevelClient sbEsClient) {
        this.props = props;
        this.outboundRequestHandlerService = outboundRequestHandlerService;
        this.objectMapper = objectMapper;
        this.sbEsClient = sbEsClient;
    }

    @Value("${smt.role.code:STATE_MASTER_TRAINER}")
    private String smtRoleCode;

    @Value("${slt.role.code:STATE_LEAD_TRAINER}")
    private String sltRoleCode;

    @Value("${smt.to.slt.min.completed.batches:3}")
    private int minCompletedBatches;

    @Value("${smt.to.slt.min.learners.trained:100}")
    private int minLearnersTrained;


    @Override
    public SBApiResponse runSmtToSltUpgradeCheck() {
        SBApiResponse response = new SBApiResponse("api.smt.slt.upgrade.run");
        int scannedCount = 0;
        int upgradedCount = 0;
        int failedCount = 0;

        try {
            List<String> smtUserIds = fetchSmtUserIds();
            logger.info("SmtToSltUpgradeJob: found {} SMT users to evaluate", smtUserIds.size());

            for (String userId : smtUserIds) {
                scannedCount++;
                UpgradeOutcome outcome = evaluateAndUpgradeUser(userId);
                if (outcome == UpgradeOutcome.UPGRADED) {
                    upgradedCount++;
                } else if (outcome == UpgradeOutcome.FAILED) {
                    failedCount++;
                }
            }
        } catch (Exception ex) {
            logger.error("SmtToSltUpgradeJob: job run failed", ex);
            response.put(Constants.ERROR_MESSAGE, ex.getMessage());
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        }

        response.put(Constants.SCANNED_COUNT, scannedCount);
        response.put(Constants.UPGRADED_COUNT, upgradedCount);
        response.put(Constants.FAILED_COUNT, failedCount);
        response.setResponseCode(HttpStatus.OK);
        return response;
    }

    private enum UpgradeOutcome {
        UPGRADED, NOT_ELIGIBLE, FAILED
    }

    /**
     * Evaluates a single SMT user's completed-batch stats and upgrades them to SLT if eligible.
     * Any failure while evaluating/upgrading this user is contained here so one bad user
     * doesn't abort the overall job run.
     */
    private UpgradeOutcome evaluateAndUpgradeUser(String userId) {
        try {
            List<Map<String, Object>> completedBatches = fetchCompletedBatchesForUser(userId);
            BatchStats stats = summarizeBatchStats(completedBatches);
            logger.info("SmtToSltUpgradeJob: userId={} completedBatchCount={} totalLearners={}",
                    userId, stats.completedBatchCount, stats.totalLearners);

            if (stats.completedBatchCount < minCompletedBatches && stats.totalLearners < minLearnersTrained) {
                return UpgradeOutcome.NOT_ELIGIBLE;
            }

            if (upgradeUserToSlt(userId)) {
                logger.info("SmtToSltUpgradeJob: upgraded userId={} from SMT to SLT "
                        + "(completedBatchCount={}, totalLearners={})", userId, stats.completedBatchCount,
                        stats.totalLearners);
                return UpgradeOutcome.UPGRADED;
            }
            logger.error("SmtToSltUpgradeJob: failed to upgrade userId={}", userId);
            return UpgradeOutcome.FAILED;
        } catch (Exception ex) {
            logger.error("SmtToSltUpgradeJob: error evaluating userId={}", userId, ex);
            return UpgradeOutcome.FAILED;
        }
    }

    private static class BatchStats {
        private final int completedBatchCount;
        private final int totalLearners;

        private BatchStats(int completedBatchCount, int totalLearners) {
            this.completedBatchCount = completedBatchCount;
            this.totalLearners = totalLearners;
        }
    }

    /**
     * De-dupes batches in case the same batch matched more than one of
     * createdBy/mentors/coTrainers for this user (e.g. created it and is also a mentor),
     * and sums up the learners trained across those distinct batches.
     */
    private BatchStats summarizeBatchStats(List<Map<String, Object>> completedBatches) {
        Set<Object> distinctBatchIds = new HashSet<>();
        int totalLearners = 0;
        for (Map<String, Object> batch : completedBatches) {
            Object batchId = batch.get(Constants.FIELD_BATCH_ID);
            if (batchId != null) {
                distinctBatchIds.add(batchId);
            }
            Object enrolmentCountObj = batch.get(Constants.FIELD_ENROLMENT_COUNT);
            if (enrolmentCountObj instanceof Number) {
                totalLearners += ((Number) enrolmentCountObj).intValue();
            }
        }
        return new BatchStats(distinctBatchIds.size(), totalLearners);
    }

    /**
     * Fetches all userIds currently holding the SMT role, via the existing user-search API
     * (private/user/v1/search), filtering on profileDetails.bpCoTrainer.
     */
    private List<String> fetchSmtUserIds() {
        List<String> userIds = new ArrayList<>();
        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> request = new HashMap<>();
        Map<String, Object> filters = new HashMap<>();
        filters.put(Constants.PROFILE_DETAILS_BP_CO_TRAINER, smtRoleCode);
        request.put(Constants.FILTERS, filters);
        request.put(Constants.LIMIT, 10000);
        requestBody.put(Constants.REQUEST, request);

        String url = props.getSbUrl() + props.getUserSearchEndPoint();
        Map<String, Object> rawResponse = outboundRequestHandlerService.fetchResultUsingPost(url, requestBody, null);
        if (MapUtils.isEmpty(rawResponse)) {
            return userIds;
        }
        SearchUserApiResp searchUserResult = objectMapper.convertValue(rawResponse, SearchUserApiResp.class);

        if (searchUserResult != null && StringUtils.equalsIgnoreCase(Constants.OK, searchUserResult.getResponseCode())
                && searchUserResult.getResult() != null
                && searchUserResult.getResult().getResponse() != null
                && CollectionUtils.isNotEmpty(searchUserResult.getResult().getResponse().getContent())) {
            for (SearchUserApiContent content : searchUserResult.getResult().getResponse().getContent()) {
                if (StringUtils.isNotBlank(content.getUserId())) {
                    userIds.add(content.getUserId());
                }
            }
        }
        return userIds;
    }

    /**
     * Queries the cbatch ES index directly for completed batches where this user is the creator,
     * a mentor, or a co-trainer.
     */
    private List<Map<String, Object>> fetchCompletedBatchesForUser(String userId) throws IOException {
        List<Map<String, Object>> batches = new ArrayList<>();

        SearchRequest searchRequest = new SearchRequest(Constants.CBATCH_INDEX);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
                .must(QueryBuilders.termQuery(Constants.FIELD_STATUS, Constants.BATCH_STATUS_COMPLETED))
                .must(QueryBuilders.boolQuery()
                        .should(QueryBuilders.termQuery(Constants.FIELD_CREATED_BY + Constants.FIELD_RAW_SUFFIX, userId))
                        .should(QueryBuilders.termQuery(Constants.FIELD_MENTORS + Constants.FIELD_RAW_SUFFIX, userId))
                        .should(QueryBuilders.termQuery(Constants.FIELD_CO_TRAINERS + Constants.FIELD_RAW_SUFFIX, userId))
                        .minimumShouldMatch(1));

        sourceBuilder.query(boolQuery);
        sourceBuilder.size(1000);
        searchRequest.source(sourceBuilder);

        SearchResponse searchResponse = executeSearch(searchRequest);
        for (SearchHit hit : searchResponse.getHits().getHits()) {
            batches.add(hit.getSourceAsMap());
        }
        return batches;
    }

    /**
     * Thin seam around the ES client call so tests can stub the search result without
     * needing to mock RestHighLevelClient's final search() method.
     */
    protected SearchResponse executeSearch(SearchRequest searchRequest) throws java.io.IOException {
        return sbEsClient.search(searchRequest, RequestOptions.DEFAULT);
    }

    /**
     * Upgrades a user's profileDetails.bpCoTrainer from SMT to SLT via the platform's private
     * user-update API, so the change is persisted to Cassandra (source of truth) and properly
     * synced to the user ES index - not a direct ES write.
     */
    private boolean upgradeUserToSlt(String userId) {
        try {
            Map<String, Object> request = new HashMap<>();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put(Constants.USER_ID, userId);
            Map<String, Object> profileDetails = new HashMap<>();
            profileDetails.put(Constants.BP_CO_TRAINER, sltRoleCode);
            requestBody.put(Constants.PROFILE_DETAILS, profileDetails);
            request.put(Constants.REQUEST, requestBody);

            Map<String, Object> readData = outboundRequestHandlerService.fetchResultUsingPatch(
                    props.getSbUrl() + props.getLmsUserUpdatePrivatePath(), request,
                    org.sunbird.common.util.ProjectUtil.getDefaultHeaders());

            return MapUtils.isNotEmpty(readData)
                    && StringUtils.equalsIgnoreCase(Constants.OK, (String) readData.get(Constants.RESPONSE_CODE));
        } catch (Exception e) {
            logger.error("Failed to upgrade bpCoTrainer for userId: {}", userId, e);
            return false;
        }
    }
}
