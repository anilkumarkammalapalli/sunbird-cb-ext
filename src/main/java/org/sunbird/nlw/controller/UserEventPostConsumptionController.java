package org.sunbird.nlw.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.sunbird.common.model.SBApiResponse;
import org.sunbird.nlw.service.PublicUserEventBulkonboardService;
import org.sunbird.nlw.service.UserEventPostConsumptionService;

import java.io.IOException;

@RestController
public class UserEventPostConsumptionController {

    @Autowired
    UserEventPostConsumptionService nlwService;

    @PostMapping("/user/event/postConsumption")
    public ResponseEntity<?> processEventUsersForCertificateAndKarmaPoints(@RequestParam(value = "file", required = true) MultipartFile multipartFile, @RequestParam(value = "eventId", required = true) String eventId, @RequestParam(value = "batchId", required = true) String batchId) throws IOException {
        SBApiResponse uploadResponse = nlwService.processEventUsersForCertificateAndKarmaPoints(multipartFile);
        return new ResponseEntity<>(uploadResponse, uploadResponse.getResponseCode());

    }
}
