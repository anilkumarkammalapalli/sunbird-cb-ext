package org.sunbird.nlw.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.sunbird.common.util.CbExtServerProperties;
import org.sunbird.common.util.Constants;
import org.sunbird.core.producer.Producer;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ClaimEventKarmaPointsServiceImpl {


    @Autowired
    Producer producer;

    @Autowired
    CbExtServerProperties serverProperties;

    public void generateKarmaPointEventAndPushToKafka(String userId, String eventId, String batchId, long ets) {

        Map<String, Object> objectMap = new HashMap<>();
        objectMap.put("user_id", userId);
        objectMap.put("ets", ets);
        objectMap.put("event_id", eventId);
        objectMap.put("batch_id", batchId);

        Map<String, Object> envelopedMap = new HashMap<>();
        envelopedMap.put(Constants.EVENT_TYPE, Constants.EVENT_TYPE_EVENT_ATTENDED);
        envelopedMap.put(Constants.DATA, objectMap);
        envelopedMap.put(Constants.KAFKA_EVENT_VERSION_KEY, serverProperties.getKafkaEventEnvelopeVersion());

        producer.pushWithKey(serverProperties.getKarmaPointsUnifiedEventTopic(), envelopedMap, userId);

    }
}
