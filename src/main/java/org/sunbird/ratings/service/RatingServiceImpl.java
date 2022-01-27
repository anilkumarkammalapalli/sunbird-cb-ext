package org.sunbird.ratings.service;

import com.datastax.driver.core.utils.UUIDs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.sunbird.cassandra.utils.CassandraOperation;
import org.sunbird.common.model.SBApiResponse;
import org.sunbird.common.util.Constants;
import org.sunbird.core.producer.Producer;
import org.sunbird.ratings.exception.ValidationException;
import org.sunbird.ratings.model.LookupRequest;
import org.sunbird.ratings.model.RatingMessage;
import org.sunbird.ratings.model.RequestRating;
import org.sunbird.ratings.responsecode.ResponseCode;
import org.sunbird.ratings.responsecode.ResponseMessage;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class RatingServiceImpl implements RatingService {
    private final ObjectMapper mapper = new ObjectMapper();

    private final Logger logger = LoggerFactory.getLogger(getClass().getName());

    @Autowired
    CassandraOperation cassandraOperation;

    @Autowired
    Producer kafkaProducer;

    @Value("${kafka.topics.parent.rating.event}")
    public String updateRatingTopicName;

    @Override
    public SBApiResponse getRatings(String activity_Id, String activity_Type, String userId) {
        SBApiResponse response = new SBApiResponse(Constants.API_RATINGS_READ);

        try {
            validateRatingsInfo(null, null, activity_Id, activity_Type, userId);
            Map<String, Object> request = new HashMap<>();
            request.put(Constants.ACTIVITY_ID, activity_Id);
            request.put(Constants.ACTIVITY_TYPE, activity_Type);
            request.put(Constants.RATINGS_USER_ID, userId);
            List<Map<String, Object>> existingDataList = cassandraOperation.getRecordsByProperties(Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_RATINGS, request, null);
            if (!existingDataList.isEmpty()) {
                response.put(Constants.MESSAGE, Constants.SUCCESSFUL);
                response.put(Constants.RESPONSE, existingDataList);
                response.setResponseCode(HttpStatus.OK);
            } else {
                String errMsg = "No ratings found for : " + activity_Id + ", activity_Type: " + activity_Type;
                response.put(Constants.MESSAGE, Constants.FAILED);
                response.getParams().setErrmsg(errMsg);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            System.out.println("The exception has occurred in read ratings" + e.getMessage());
            e.printStackTrace();
        }
        return response;
    }

    @Override
    public SBApiResponse getRatingSummary(String activity_Id, String activity_Type) {
        SBApiResponse response = new SBApiResponse(Constants.API_RATINGS_SUMMARY);

        try {
            validateRatingsInfo(null, null, activity_Id, activity_Type, null);
            Map<String, Object> request = new HashMap<>();
            request.put(Constants.ACTIVITY_ID, activity_Id);
            request.put(Constants.ACTIVITY_TYPE, activity_Type);

            List<Map<String, Object>> existingDataList = cassandraOperation.getRecordsByProperties(Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_RATINGS_SUMMARY, request, null);
            if (!existingDataList.isEmpty()) {
                response.put(Constants.MESSAGE, Constants.SUCCESSFUL);
                response.put(Constants.RESPONSE, existingDataList);
                response.setResponseCode(HttpStatus.OK);
            } else {
                String errMsg = "No reviews found for : " + activity_Id + ", activity_Type: " + activity_Type;
                response.put(Constants.MESSAGE, Constants.FAILED);
                response.getParams().setErrmsg(errMsg);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            System.out.println("The exception has occurred in read ratings" + e.getMessage());
            e.printStackTrace();
        }
        return response;
    }

    @Override
    public SBApiResponse upsertRating(RequestRating requestRating) {
        UUID timeBasedUuid = UUIDs.timeBased();
        SBApiResponse response = new SBApiResponse(Constants.API_RATINGS_UPDATE);

        RatingMessage ratingMessage;

        try {
            validateRatingsInfo(requestRating, null, null, null, null);

            Map<String, Object> request = new HashMap<>();
            request.put(Constants.ACTIVITY_ID, requestRating.getActivity_Id());
            request.put(Constants.ACTIVITY_TYPE, requestRating.getActivity_type());
            request.put(Constants.RATINGS_USER_ID, requestRating.getUserId());


            List<Map<String, Object>> existingDataList = cassandraOperation.getRecordsByProperties(Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_RATINGS, request, null);

            if (!existingDataList.isEmpty()) {

                Map<String, Object> updateRequest = new HashMap<>();
                updateRequest.put(Constants.RATING, requestRating.getRating());
                updateRequest.put(Constants.REVIEW, requestRating.getReview());
                updateRequest.put(Constants.UPDATED_ON, timeBasedUuid);

                Map<String, Object> prevInfo = existingDataList.get(0);
                cassandraOperation.updateRecord(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_RATINGS, updateRequest,
                        request);
                ratingMessage = new RatingMessage("ratingUpdate", requestRating.getActivity_Id(), requestRating.getActivity_type(),
                        requestRating.getUserId(), String.valueOf((prevInfo.get("createdon"))));

                ratingMessage.setPrevValues(processEventMessage(String.valueOf(prevInfo.get("createdon")),
                        (Float) prevInfo.get("rating"), (String) prevInfo.get("review")));
                ratingMessage.setUpdatedValues(processEventMessage(String.valueOf(updateRequest.get(Constants.UPDATED_ON)),
                        requestRating.getRating(), requestRating.getReview()));
            } else {
                request.put(Constants.CREATED_ON, timeBasedUuid);
                request.put(Constants.RATING, requestRating.getRating());
                request.put(Constants.REVIEW, requestRating.getReview());
                request.put(Constants.UPDATED_ON, timeBasedUuid);

                cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_RATINGS, request);

                ratingMessage = new RatingMessage("ratingAdd", requestRating.getActivity_Id(), requestRating.getActivity_type(),
                        requestRating.getUserId(), String.valueOf(timeBasedUuid));

                ratingMessage.setUpdatedValues(processEventMessage(String.valueOf(request.get(Constants.CREATED_ON)),
                        requestRating.getRating(), requestRating.getReview()));
                response.put(Constants.DATA, request);

            }
            response.setResponseCode(HttpStatus.OK);
            response.getParams().setStatus(Constants.SUCCESSFUL);
            kafkaProducer.push(updateRatingTopicName, ratingMessage);
        } catch (ValidationException ex) {
            return processExceptionBody(response, ex, "", HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            String errMsg = "Exception occurred while adding the course review : ";
            return processExceptionBody(response, ex, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return response;
    }

    @Override
    public SBApiResponse search(LookupRequest lookupRequest) {

        SBApiResponse response = new SBApiResponse(Constants.API_RATINGS_LOOKUP);

        try {
            validateRatingsInfo(null, lookupRequest, null, null, null);
            Map<String, Object> request = new HashMap<>();
            request.put(Constants.ACTIVITY_ID, lookupRequest.getActivity_Id());
            request.put(Constants.ACTIVITY_TYPE, lookupRequest.getActivity_Type());
            request.put(Constants.RATING, lookupRequest.getRating());

            List<Map<String, Object>> existingDataList = cassandraOperation.getRecordsByPropertiesWithPagination(Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_RATINGS_LOOKUP, request, null, lookupRequest.getLimit(), lookupRequest.getUpdateOn());
            if (!existingDataList.isEmpty()) {
                response.put(Constants.MESSAGE, Constants.SUCCESSFUL);
                response.put(Constants.RESPONSE, existingDataList);
                response.setResponseCode(HttpStatus.OK);
            } else {
                String errMsg = "No ratings found for : " + lookupRequest.getActivity_Id() + ", activity_Type: " + lookupRequest.getActivity_Type();
                response.put(Constants.MESSAGE, Constants.FAILED);
                response.getParams().setErrmsg(errMsg);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
            }
        } catch (ValidationException ex) {
            return processExceptionBody(response, ex, "", HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            System.out.println("The exception has occurred in lookup ratings" + e.getMessage());
            e.printStackTrace();
        }
        return response;

    }

    public RatingMessage.UpdatedValues processEventMessage(String date, Float rating, String review) {
        RatingMessage.UpdatedValues values = new RatingMessage.UpdatedValues();
        values.setUpdatedOn(date);
        values.setRating(rating);
        values.setReview(review);
        return values;
    }

    private void validateRatingsInfo(RequestRating requestRating, LookupRequest lookupRequest,
                                     String activity_Id, String activity_Type, String userId) throws Exception {

        List<String> errObjList = new ArrayList<>();

        if (requestRating != null) {
            if (StringUtils.isEmpty(requestRating.getActivity_Id())) {
                errObjList.add(ResponseMessage.Message.INVALID_INPUT);
            }
            if (StringUtils.isEmpty(requestRating.getActivity_type())) {
                errObjList.add(ResponseMessage.Message.INVALID_INPUT);
            }
            if (StringUtils.isEmpty((String.valueOf(requestRating.getRating()))) || requestRating.getRating() < 1
                    || requestRating.getRating() > 5) {
                errObjList.add(ResponseMessage.Message.INVALID_INPUT + ResponseMessage.Message.INVALID_RATING);
            }
            if (StringUtils.isEmpty(requestRating.getReview()) || (!Pattern.matches("^[A-Za-z0-9, ]++$", requestRating.getReview()))) {
                errObjList.add(ResponseMessage.Message.INVALID_REVIEW);
            }
            if (StringUtils.isEmpty(requestRating.getUserId())) {
                errObjList.add(ResponseMessage.Message.INVALID_USER);
            }
        }
        if (lookupRequest != null) {

            if (StringUtils.isEmpty(lookupRequest.getActivity_Id())) {
                errObjList.add(ResponseMessage.Message.INVALID_INPUT);
            }
            if (StringUtils.isEmpty(lookupRequest.getActivity_Type())) {
                errObjList.add(ResponseMessage.Message.INVALID_INPUT);
            }
            if ((lookupRequest.getRating() < 1.0
                    || lookupRequest.getRating() > 5.0)) {
                errObjList.add(ResponseMessage.Message.INVALID_INPUT + ResponseMessage.Message.INVALID_RATING);
            }
            if (lookupRequest.getLimit() < 1) {
                errObjList.add(ResponseMessage.Message.INVALID_LIMIT);
            }
            if (StringUtils.isEmpty(lookupRequest.getUpdateOn())) {
                errObjList.add(ResponseMessage.Message.INVALID_INPUT);
            }
        }
        if (activity_Id != null && activity_Type != null) {
            if (StringUtils.isEmpty(activity_Id)) {
                errObjList.add(ResponseMessage.Message.INVALID_INPUT);
            }
            if (StringUtils.isEmpty(activity_Type)) {
                errObjList.add(ResponseMessage.Message.INVALID_INPUT);
            }
        }
        if (userId != null) {
            if (StringUtils.isEmpty(userId)) {
                errObjList.add(ResponseMessage.Message.INVALID_INPUT);
            }
        }
        if (!CollectionUtils.isEmpty(errObjList)) {
            throw new ValidationException(errObjList, ResponseCode.BAD_REQUEST.getResponseCode());
        }
    }

    public SBApiResponse processExceptionBody(SBApiResponse response, Exception ex,
                                              String exceptionMessage, HttpStatus status) {
        String errMsg = exceptionMessage + ex.getMessage();
        logger.error(errMsg, ex.toString());
        response.getParams().setErrmsg(errMsg);
        response.setResponseCode(status);
        return response;
    }

}
