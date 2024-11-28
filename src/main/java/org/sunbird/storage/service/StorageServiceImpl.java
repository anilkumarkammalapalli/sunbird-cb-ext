package org.sunbird.storage.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.sunbird.cloud.storage.BaseStorageService;
import org.sunbird.cloud.storage.Model;
import org.sunbird.cloud.storage.factory.StorageConfig;
import org.sunbird.cloud.storage.factory.StorageServiceFactory;
import org.sunbird.common.model.SBApiResponse;
import org.sunbird.common.util.AccessTokenValidator;
import org.sunbird.common.util.CbExtServerProperties;
import org.sunbird.common.util.Constants;

import org.sunbird.common.util.ProjectUtil;
import org.sunbird.user.service.UserUtilityService;
import scala.Option;

@Service
public class StorageServiceImpl implements StorageService {

	private Logger logger = LoggerFactory.getLogger(getClass().getName());
	private BaseStorageService storageService = null;

	@Autowired
	RestTemplate restTemplate;

	@Autowired
	private CbExtServerProperties serverProperties;

	@Autowired
	private AccessTokenValidator accessTokenValidator;

	@Autowired
	private UserUtilityService userUtilityService;

	@PostConstruct
	public void init() {
		if (storageService == null) {
			storageService = StorageServiceFactory.getStorageService(new StorageConfig(
					serverProperties.getCloudStorageTypeName(), serverProperties.getCloudStorageKey(),
					serverProperties.getCloudStorageSecret().replace("\\n", "\n"), Option.apply(serverProperties.getCloudStorageEndpoint()), Option.empty()));
		}
	}

	@Override
	public SBApiResponse uploadFile(MultipartFile mFile, String cloudFolderName) throws IOException {
		return uploadFile(mFile, cloudFolderName, serverProperties.getCloudContainerName());
	}

	public SBApiResponse uploadFile(MultipartFile mFile, String cloudFolderName, String containerName) throws IOException {
		SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_FILE_UPLOAD);
		File file = null;
		try {
			file = new File(System.currentTimeMillis() + "_" + mFile.getOriginalFilename());
			file.createNewFile();
			// Use try-with-resources to ensure FileOutputStream is closed
			try (FileOutputStream fos = new FileOutputStream(file)) {
				fos.write(mFile.getBytes());
			}
			return uploadFile(file, cloudFolderName, containerName);
		} catch (Exception e) {
			logger.error("Failed to upload file. Exception: ", e);
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Failed to upload file. Exception: " + e.getMessage());
			response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
			return response;
		} finally {
			if (file != null && file.exists()) {
				file.delete();
			}
		}
	}

	@Override
	public SBApiResponse uploadFile(File file, String cloudFolderName, String containerName) {
		SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_FILE_UPLOAD);
		try {
			String objectKey = cloudFolderName + "/" + file.getName();
			String url = storageService.upload(containerName, file.getAbsolutePath(),
					objectKey, Option.apply(false), Option.apply(1), Option.apply(5), Option.empty());
			Map<String, String> uploadedFile = new HashMap<>();
			uploadedFile.put(Constants.NAME, file.getName());
			uploadedFile.put(Constants.URL, url);
			response.getResult().putAll(uploadedFile);
			return response;
		} catch (Exception e) {
			logger.error("Failed to upload file. Exception: ", e);
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Failed to upload file. Exception: " + e.getMessage());
			response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
			return response;
		} finally {
			if (file != null) {
				file.delete();
			}
		}
	}

	@Override
	public SBApiResponse deleteFile(String fileName, String containerName) {
		SBApiResponse response = new SBApiResponse();
		response.setId(Constants.API_FILE_DELETE);
		try {
			String objectKey = serverProperties.getCloudContainerName() + "/" + fileName;
			storageService.deleteObject(serverProperties.getCloudContainerName(), objectKey,
					Option.apply(Boolean.FALSE));
			response.getParams().setStatus(Constants.SUCCESSFUL);
			response.setResponseCode(HttpStatus.OK);
			return response;
		} catch (Exception e) {
			logger.error("Failed to delete file: " + fileName + ", Exception: ", e);
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Failed to delete file. Exception: " + e.getMessage());
			response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
			return response;
		}
	}
	@Override
	public SBApiResponse downloadFile(String fileName) {
		SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_FILE_DOWNLOAD);
		try {
			String objectKey = serverProperties.getBulkUploadContainerName() + "/" + fileName;
			storageService.download(serverProperties.getCloudContainerName(), objectKey, Constants.LOCAL_BASE_PATH,
					Option.apply(Boolean.FALSE));
			return response;
		} catch (Exception e) {
			logger.error("Failed to download the file: " + fileName + ", Exception: ", e);
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Failed to download the file. Exception: " + e.getMessage());
			response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
			return response;
		}
	}

	@Override
	public ResponseEntity<Resource> downloadFile(String reportType, String date, String orgId, String fileName, String userToken) {
		try {
			String userId = accessTokenValidator.fetchUserIdFromAccessToken(userToken);
			if (StringUtils.isEmpty(userId)) {
				logger.error("Failed to get UserId for orgId: " + orgId);
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
			}
			Map<String, Map<String, String>> userInfoMap = new HashMap<>();
			userUtilityService.getUserDetailsFromDB(Arrays.asList(userId), Arrays.asList(Constants.USER_ID, Constants.ROOT_ORG_ID), userInfoMap);
			if (MapUtils.isEmpty(userInfoMap)) {
				logger.error("Failed to get UserInfo from cassandra for userId: " + userId);
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
			}
			String rootOrgId = userInfoMap.get(userId).get(Constants.ROOT_ORG_ID);
			if (StringUtils.isNotEmpty(orgId)) {
				if (orgId.contains("=")) {
					rootOrgId = "mdoid=" + rootOrgId;
				}
			}
			if (!rootOrgId.equalsIgnoreCase(orgId)) {
				logger.error("User is not authorized to download the file for other org: " + rootOrgId + ", request orgId " + orgId);
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
			}
			String objectKey = "";
			if (serverProperties.getReportPropertyFileAllMdo().contains(fileName)) {
				String reportSubFolderName = fileName.replace(".csv", "");
				objectKey = serverProperties.getReportDownloadFolderName() + "/" + reportType + "/" + date + "/" + reportSubFolderName + "/" + fileName;
			} else {
				objectKey = serverProperties.getReportDownloadFolderName() + "/" + reportType + "/" + date + "/" + orgId + "/" + fileName;
			}

			storageService.download(serverProperties.getReportDownloadContainerName(), objectKey, Constants.LOCAL_BASE_PATH,
					Option.apply(Boolean.FALSE));
			Path tmpPath = Paths.get(Constants.LOCAL_BASE_PATH + fileName);
			ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(tmpPath));
			HttpHeaders headers = new HttpHeaders();
			headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
			return ResponseEntity.ok()
					.headers(headers)
					.contentLength(tmpPath.toFile().length())
					.contentType(MediaType.parseMediaType(MediaType.MULTIPART_FORM_DATA_VALUE))
					.body(resource);
		} catch (Exception e) {
			logger.error("Failed to read the downloaded file: " + fileName + ", Exception: ", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		} finally {
			try {
				File file = new File(Constants.LOCAL_BASE_PATH + fileName);
				if(file.exists()) {
					file.delete();
				}
			} catch(Exception e1) {
			}
		}
	}

	protected void finalize() {
		try {
			if (storageService != null) {
				storageService.closeContext();
				storageService = null;
			}
		} catch (Exception e) {
		}
	}

	@Override
	public ResponseEntity<Map<String, Map<String, Object>>> getFileInfo(String orgId) {
		Map<String, String> reportFileNameMap = serverProperties.getReportMap();
		Map<String, Map<String, Object>> reportTypeInfo = new HashMap<>();
		for (String reportType : serverProperties.getReportTypeGetFileInfo()) {
			Map<String, Object> resourceMap = new HashMap<>();
			LocalDateTime now = LocalDateTime.now();
			DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd");
			String todayFormattedDate = now.format(dateFormat);
			String mdoId = "mdoid=" + orgId;
				/*if (orgId != null && !orgId.isEmpty()) {
					if (orgId.contains("=")) {
						String[] array = orgId.split("=");
						mdoId = array[1];
					} else {
						mdoId = orgId;
					}
				}*/
			String fileName = reportFileNameMap.get(reportType);
			String objectKey = "";
			if (serverProperties.getReportPropertyFileAllMdo().contains(fileName)) {
				String reportSubFolderName = fileName.replace(".csv", "");
				objectKey = serverProperties.getReportDownloadFolderName() + "/" + reportType + "/" + todayFormattedDate + "/" + reportSubFolderName + "/" + fileName;
			} else {
				objectKey = serverProperties.getReportDownloadFolderName() + "/" + reportType + "/" + todayFormattedDate + "/" + mdoId + "/" + fileName;
			}
			try {
				Model.Blob blob = storageService.getObject(serverProperties.getReportDownloadContainerName(), objectKey, Option.apply(Boolean.FALSE));
				if (blob != null) {
					resourceMap.put("lastModified", blob.lastModified());
					resourceMap.put("fileMetaData", blob.metadata());
				}
			} catch (Exception e) {
				logger.error("Failed to read the downloaded file for url: " + objectKey);
				LocalDateTime yesterday = now.minusDays(1);
				String yesterdayFormattedDate = yesterday.format(dateFormat);
				if (serverProperties.getReportPropertyFileAllMdo().contains(fileName)) {
					String reportSubFolderName = fileName.replace(".csv", "");
					objectKey = serverProperties.getReportDownloadFolderName() + "/" + reportType + "/" + yesterdayFormattedDate + "/" + reportSubFolderName + "/" + fileName;
				} else {
					objectKey = serverProperties.getReportDownloadFolderName() + "/" + reportType + "/" + yesterdayFormattedDate + "/" + mdoId + "/" + fileName;
				}
				try {
					Model.Blob blob = storageService.getObject(serverProperties.getReportDownloadContainerName(), objectKey, Option.apply(Boolean.FALSE));
					if (blob != null) {
						resourceMap.put("lastModified", blob.lastModified());
						resourceMap.put("fileMetaData", blob.metadata());
					} else {
						resourceMap.put("msg", "No Report Available");
						logger.info("Unable to fetch fileInfo");
					}
				} catch (Exception ex) {
					logger.error("Failed to read the downloaded file for url: " + objectKey);
				}
			}
			reportTypeInfo.put(fileName, resourceMap);
		}
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.body(reportTypeInfo);

	}

	@Override
	public ResponseEntity<?> downloadFile(String reportType, String date, String fileName, String userToken) {
		try {
			String userId = accessTokenValidator.fetchUserIdFromAccessToken(userToken);
			if (StringUtils.isEmpty(userId)) {
				logger.error("Failed to get user");
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
			}
			Map<String, Map<String, String>> userInfoMap = new HashMap<>();
			userUtilityService.getUserDetailsFromDB(Arrays.asList(userId), Arrays.asList(Constants.USER_ID, Constants.CHANNEL), userInfoMap);
			if (MapUtils.isEmpty(userInfoMap)) {
				logger.error("Failed to get UserInfo from cassandra for userId: " + userId);
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
			}
			String channel = userInfoMap.get(userId).get(Constants.CHANNEL);

			if (!serverProperties.getSpvChannelName().equalsIgnoreCase(channel)) {
				logger.error("User is not authorized to download the file for other org: ");
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
			}
			Map<String, String> spvReportSubFolderTypeMap = serverProperties.getSpvReportSubFolderTypeMap();
			String objectKey = serverProperties.getReportDownloadFolderName() + "/" + spvReportSubFolderTypeMap.get(fileName) + "/" + date + "/" + reportType + "/" + fileName;
			storageService.download(serverProperties.getReportDownloadContainerName(), objectKey, Constants.LOCAL_BASE_PATH,
					Option.apply(Boolean.FALSE));
			Path tmpPath = Paths.get(Constants.LOCAL_BASE_PATH + fileName);
			ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(tmpPath));
			HttpHeaders headers = new HttpHeaders();
			headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
			return ResponseEntity.ok()
					.headers(headers)
					.contentLength(tmpPath.toFile().length())
					.contentType(MediaType.parseMediaType(MediaType.MULTIPART_FORM_DATA_VALUE))
					.body(resource);
		} catch (Exception e) {
			logger.error("Failed to read the downloaded file: " + fileName + ", Exception: ", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		} finally {
			try {
				File file = new File(Constants.LOCAL_BASE_PATH + fileName);
				if (file.exists()) {
					file.delete();
				}
			} catch (Exception e1) {
			}
		}

	}

	@Override
	public ResponseEntity<?> getFileInfoSpv(String userToken, String date) {
		String userId = accessTokenValidator.fetchUserIdFromAccessToken(userToken);
		if (StringUtils.isEmpty(userId)) {
			logger.error("Failed to get user");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		Map<String, Map<String, String>> userInfoMap = new HashMap<>();
		userUtilityService.getUserDetailsFromDB(Arrays.asList(userId), Arrays.asList(Constants.USER_ID, Constants.CHANNEL), userInfoMap);
		if (MapUtils.isEmpty(userInfoMap)) {
			logger.error("Failed to get UserInfo from cassandra for userId: " + userId);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
		String channel = userInfoMap.get(userId).get(Constants.CHANNEL);

		if (!serverProperties.getSpvChannelName().equalsIgnoreCase(channel)) {
			logger.error("User is not authorized to download the file for other org: ");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		Map<String, String> reportFileNameMap = serverProperties.getSpvReportMap();
		Map<String, String> spvReportSubFolderTypeMap = serverProperties.getSpvReportSubFolderTypeMap();
		Map<String, Map<String, Object>> reportTypeInfo = new HashMap<>();
		for (Map.Entry<String, String> entry : reportFileNameMap.entrySet()) {
			Map<String, Object> resourceMap = new HashMap<>();
			String fileName = entry.getValue();
			String reportType = "";
			if (fileName.contains(".")) {
				String fileExtension = fileName.substring(fileName.lastIndexOf(".") + 1);
				reportType = entry.getValue().replace("." + fileExtension, "");
			}

			String objectKey = serverProperties.getReportDownloadFolderName() + "/" + spvReportSubFolderTypeMap.get(fileName) + "/" + date + "/" + reportType + "/" + fileName;
			try {
				Model.Blob blob = storageService.getObject(serverProperties.getReportDownloadContainerName(), objectKey, Option.apply(Boolean.FALSE));
				if (blob != null) {
					resourceMap.put("lastModified", blob.lastModified());
					resourceMap.put("fileMetaData", blob.metadata());
				}
			} catch (Exception e) {
				logger.error("Failed to read the downloaded file for url: " + objectKey);
			}
			reportTypeInfo.put(fileName, resourceMap);
		}
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.body(reportTypeInfo);
	}

	@Override
	public SBApiResponse downloadFile(String fileName, String containerName) {
		SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_FILE_DOWNLOAD);
		try {
			String objectKey = containerName + "/" + fileName;
			storageService.download(serverProperties.getCloudContainerName(), objectKey, Constants.LOCAL_BASE_PATH,
					Option.apply(Boolean.FALSE));
			return response;
		} catch (Exception e) {
			logger.error("Failed to download the file: " + fileName + ", Exception: ", e);
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Failed to download the file. Exception: " + e.getMessage());
			response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
			return response;
		}
	}

	@Override
	public SBApiResponse uploadFileForOrg(MultipartFile mFile, String userToken) {
		SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_FILE_UPLOAD);
		try {
			String userId = accessTokenValidator.fetchUserIdFromAccessToken(userToken);
			if (StringUtils.isEmpty(userId)) {
				logger.error("Failed to get the UserInfo from token");
				response.getParams().setStatus(Constants.FAILED);
				response.getParams().setErrmsg("Failed to get the UserInfo from token");
				response.setResponseCode(HttpStatus.UNAUTHORIZED);
				return response;
			}
			Map<String, Map<String, String>> userInfoMap = new HashMap<>();
			userUtilityService.getUserDetailsFromDB(Arrays.asList(userId), Arrays.asList(Constants.USER_ID, Constants.ROOT_ORG_ID), userInfoMap);
			if (MapUtils.isEmpty(userInfoMap)) {
				logger.error("Failed to get the UserInfo from token");
				response.getParams().setStatus(Constants.FAILED);
				response.getParams().setErrmsg("Failed to get the UserInfo from token");
				response.setResponseCode(HttpStatus.UNAUTHORIZED);
				return response;
			}
			String orgId = userInfoMap.get(userId).get(Constants.ROOT_ORG_ID);
			return uploadFile(mFile, serverProperties.getOrgStoreFolderName() + "/" + orgId, serverProperties.getCloudPublicContainerName());
		} catch (IOException e) {
			logger.error("Failed to upload the file, Exception: ", e);
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Failed to upload the file, Exception: " + e.getMessage());
			response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
			return response;
		}
	}

	@Override
	public SBApiResponse ciosContentIconUpload(MultipartFile mFile, String containerName, String cloudFolderName) {
		SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_FILE_UPLOAD);
		File file = null;
		try {
			file = new File(System.currentTimeMillis() + "_" + mFile.getOriginalFilename());
			file.createNewFile();
			FileOutputStream fos = new FileOutputStream(file);
			fos.write(mFile.getBytes());
			fos.close();
			return uploadFile(file, cloudFolderName, containerName);
		} catch (Exception e) {
			logger.error("Failed to upload file. Exception: ", e);
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Failed to upload file. Exception: " + e.getMessage());
			response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
			return response;
		} finally {
			if (file != null) {
				file.delete();
			}
		}
	}

	@Override
	public SBApiResponse ciosContentContractUpload(MultipartFile mFile, String containerName, String cloudFolderName) {
		SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_FILE_UPLOAD);
		File file = null;
		try {
			file = new File(System.currentTimeMillis() + "_" + mFile.getOriginalFilename());
			file.createNewFile();
			FileOutputStream fos = new FileOutputStream(file);
			fos.write(mFile.getBytes());
			fos.close();
			return uploadFile(file, cloudFolderName, containerName);
		} catch (Exception e) {
			logger.error("Failed to upload file. Exception: ", e);
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Failed to upload file. Exception: " + e.getMessage());
			response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
			return response;
		} finally {
			if (file != null) {
				file.delete();
			}
		}
	}

	@Override
	public ResponseEntity<?> downloadCiosContractFile(String fileName) {
		try {
			String objectKey = serverProperties.getCiosCloudFolderName() + "/" + fileName;
			storageService.download(serverProperties.getCiosCloudContainerName(), objectKey, Constants.LOCAL_BASE_PATH,
					Option.apply(Boolean.FALSE));
			Path tmpPath = Paths.get(Constants.LOCAL_BASE_PATH + fileName);
			ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(tmpPath));
			HttpHeaders headers = new HttpHeaders();
			headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
			return ResponseEntity.ok()
					.headers(headers)
					.contentLength(tmpPath.toFile().length())
					.contentType(MediaType.parseMediaType(MediaType.MULTIPART_FORM_DATA_VALUE))
					.body(resource);
		} catch (Exception e) {
			logger.error("Failed to read the downloaded file: " + fileName + ", Exception: ", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		} finally {
			try {
				File file = new File(Constants.LOCAL_BASE_PATH + fileName);
				if (file.exists()) {
					file.delete();
				}
			} catch (Exception e1) {
			}
		}

	}

	@Override
	public SBApiResponse uploadCiosLogsFile(String logFilePath, String fileName, String containerName, String cloudFolderName) {
		SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_FILE_UPLOAD);
		File file = null;
		File tempFile = null;
		try {
			file = new File(logFilePath);
			if (!file.exists()) {
				response.getParams().setStatus(Constants.FAILED);
				response.getParams().setErrmsg("Log file not found at the specified path: " + logFilePath);
				logger.error("Log file not found at the specified path: {}" + logFilePath);
				response.setResponseCode(HttpStatus.NOT_FOUND);
				return response;
			}
			tempFile = new File(System.currentTimeMillis() + "_" + fileName);
			tempFile.createNewFile();
			try (FileInputStream fis = new FileInputStream(file);
				 FileOutputStream fos = new FileOutputStream(tempFile)) {
				byte[] buffer = new byte[1024];
				int length;
				while ((length = fis.read(buffer)) > 0) {
					fos.write(buffer, 0, length);
				}
			}
			SBApiResponse uploadResponse = uploadFile(tempFile, cloudFolderName, containerName);
			if (uploadResponse.getParams().getStatus().equals(Constants.FAILED)) {
				throw new IOException("Failed to upload log file to GCP bucket.");
			}
			response = uploadResponse;
		} catch (Exception e) {
			logger.error("Failed to upload log file. Exception: ", e);
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Failed to upload log file. Exception: " + e.getMessage());
			response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
		} finally {
			if (file != null && file.exists()) {
				file.delete();
			}
			if (tempFile != null && tempFile.exists()) {
				tempFile.delete();
			}
		}

		return response;
	}

	@Override
	public ResponseEntity<?> downloadCiosLogsFile(String fileName) {
		Path tmpPath = Paths.get(Constants.LOCAL_BASE_PATH + fileName);
		try {
			String objectKey = serverProperties.getCiosFileLogsCloudFolderName() + "/" + fileName;
			storageService.download(serverProperties.getCiosCloudContainerName(), objectKey, Constants.LOCAL_BASE_PATH,
					Option.apply(Boolean.FALSE));

			ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(tmpPath));
			HttpHeaders headers = new HttpHeaders();
			headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
			return ResponseEntity.ok()
					.headers(headers)
					.contentLength(tmpPath.toFile().length())
					.contentType(MediaType.parseMediaType(MediaType.MULTIPART_FORM_DATA_VALUE))
					.body(resource);
		} catch (Exception e) {
			logger.error("Failed to read the downloaded file: " + fileName + ", Exception: ", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		} finally {
			try {
				Files.deleteIfExists(tmpPath);
			} catch (IOException e) {
				logger.error("Failed to delete the temporary file: " + fileName + ", Exception: ", e);
			}
		}
	}

	/**
	 * Uploads an image to a GCP container.
	 *
	 * @param multipartFile the image file to be uploaded
	 * @param requestBody   the request body containing cloud folder name and container name
	 * @param authUserToken the authentication token for the user
	 * @return the API response
	 */
	@Override
	public SBApiResponse uploadImageToGCPContainer(MultipartFile multipartFile, Map<String, Object> requestBody, String authUserToken) {
		SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.IMAGE_UPLOAD_GCP_CONTAINER);
		try {
			String userId = fetchUserIdFromToken(authUserToken, response);
			if (userId == null) {
				logger.error("Failed to fetch user ID from token");
				return response;
			}
			String errMsg = validateRequestFields(requestBody, response);
			if (!StringUtils.isEmpty(errMsg)) {
				logger.error("Invalid request body: {}", errMsg);
				return response;
			}
			File file = File.createTempFile(String.valueOf(System.currentTimeMillis()), multipartFile.getOriginalFilename());
			try (FileOutputStream fos = new FileOutputStream(file)) {
				logger.info("Wrote image to temporary file: {}", file.getAbsolutePath());
				fos.write(multipartFile.getBytes());
				return uploadFile(file, (String) requestBody.get("cloudFolderName"), (String) requestBody.get("containerName"));
			}
		} catch (Exception e) {
			logger.error("Failed to upload file. Exception: ", e);
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Failed to upload file. Exception: " + e.getMessage());
			response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
			return response;
		}
	}

	/**
	 * Validates the request fields for uploading an image to a GCP container.
	 *
	 * @param request  the request map containing the cloud folder and container names
	 * @param response the response object to be updated with error information
	 * @return an error message if the request fields are invalid, otherwise an empty string
	 */
	private String validateRequestFields(Map<String, Object> request, SBApiResponse response) {
		if (StringUtils.isEmpty((String) request.get("cloudFolderName"))) {
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Cloud folder name is missing");
			response.setResponseCode(HttpStatus.BAD_REQUEST);
			return "Cloud folder name is missing";
		} else if (StringUtils.isEmpty((String) request.get("containerName"))) {
			response.getParams().setStatus(Constants.FAILED);
			response.getParams().setErrmsg("Cloud container name is missing");
			response.setResponseCode(HttpStatus.BAD_REQUEST);
			return "Cloud container name is missing";
		}
		return "";
	}

	/**
	 * Fetches the user ID from the provided authentication token.
	 *
	 * @param authUserToken the authentication token to extract the user ID from
	 * @param response      the API response object to update with error details if necessary
	 * @return the user ID extracted from the token, or null if the token is invalid
	 */
	private String fetchUserIdFromToken(String authUserToken, SBApiResponse response) {
		String userId = accessTokenValidator.fetchUserIdFromAccessToken(authUserToken);
		if (ObjectUtils.isEmpty(userId)) {
			updateErrorDetails(response, HttpStatus.BAD_REQUEST);
		}
		return userId;
	}

	/**
	 * Updates the error details in the API response.
	 *
	 * @param response     The API response object.
	 * @param responseCode The HTTP status code.
	 */
	private void updateErrorDetails(SBApiResponse response, HttpStatus responseCode) {
		response.getParams().setStatus(Constants.FAILED);
		response.getParams().setErrmsg(Constants.USER_ID_DOESNT_EXIST);
		response.setResponseCode(responseCode);
	}
}
