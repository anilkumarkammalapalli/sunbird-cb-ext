package org.sunbird.customselfregistration.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.sunbird.common.model.SBApiResponse;
import org.sunbird.customselfregistration.service.CustomSelfRegistrationService;

import javax.validation.Valid;
import java.io.IOException;
import java.util.Map;

/**
 * @author mahesh.vakkund
 */
@RestController
public class CustomSelfRegistrationController {

    @Autowired
    CustomSelfRegistrationService customSelfRegistrationService;

    @PostMapping(value = "/getSelfRegistrationQRPdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<SBApiResponse> getBatchSessionQRPdf(@RequestHeader("x-authenticated-user-token") String authUserToken, @Valid @RequestBody Map<String, Object> requestBody) throws IOException {
        return new ResponseEntity<>(customSelfRegistrationService.getSelfRegistrationQRAndLink(authUserToken,requestBody), HttpStatus.OK);
    }
}
