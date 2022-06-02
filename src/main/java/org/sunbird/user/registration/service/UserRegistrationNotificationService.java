package org.sunbird.user.registration.service;

import org.springframework.stereotype.Service;
import org.sunbird.user.registration.model.UserRegistration;

/**
 * Provides APIs to send out notification for user registration
 * 
 * @author karthik
 *
 */
@Service
public interface UserRegistrationNotificationService {

	public void sendNotification(UserRegistration userRegistration);
}