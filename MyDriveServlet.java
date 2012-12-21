package mydrive;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FilenameUtils;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.dynamodb.AmazonDynamoDB;
import com.amazonaws.services.dynamodb.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodb.model.AttributeValue;
import com.amazonaws.services.dynamodb.model.DeleteItemRequest;
import com.amazonaws.services.dynamodb.model.DeleteItemResult;
import com.amazonaws.services.dynamodb.model.GetItemRequest;
import com.amazonaws.services.dynamodb.model.GetItemResult;
import com.amazonaws.services.dynamodb.model.Key;
import com.amazonaws.services.dynamodb.model.PutItemRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.BucketVersioningConfiguration;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.S3VersionSummary;
import com.amazonaws.services.s3.model.SetBucketVersioningConfigurationRequest;
import com.amazonaws.services.s3.model.VersionListing;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.CreateTopicRequest;
import com.amazonaws.services.sns.model.CreateTopicResult;
import com.amazonaws.services.sns.model.DeleteTopicRequest;
import com.amazonaws.services.sns.model.PublishRequest;

@SuppressWarnings("serial")
public class MyDriveServlet extends HttpServlet {
	private AmazonS3 s3;
	private AmazonDynamoDB dynamoDB;
	private AmazonSNS sns;
	private AWSCredentials credentials;
	private ServletFileUpload uploadHandler;
	
	private static final String userTableName = "mydriveusers";
	private static final String SNS_TOPIC = "sns_topic";
	private static final String TOPIC_ARN = "sns_topicArn";
	private static final String USER_NAME = "username";
	private static final String PASSWORD = "password";
	private static final String S3_BUCKET_NAME = "s3bucket";
	private static final String COMMAND = "command";
	private static final String FILES = "files";
	private static final String FILE_NAME = "filename";
	private static final String TMP_DIR_PATH = "/tmp";
	
	private static final String FILE_UPLOADED = "0";
	private static final String FILE_DELETED = "1";
	
	private static final int CMD_USER_REGISTER = 0;
	private static final int CMD_USER_SIGNIN = 1;
	private static final int CMD_GET_VERSION = 2;
	private static final int CMD_GET_URL = 3;
	private static final int CMD_UPLOAD_FILE = 4;
	private static final int CMD_DELETE_FILE = 5;
	private static final int CMD_USER_DEREGISTER = 6;
	
	
	private static final String SUCCESS_EC = "0";
	private static final String FAILURE_EC = "1";
	
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		try {
			credentials = new PropertiesCredentials(
					getClass().getClassLoader().getResourceAsStream("AwsCredentials.properties"));
		} catch (IOException e) {
			throw new ServletException("Error when reading credentials", e);
		}
		s3 = new AmazonS3Client(credentials);
		dynamoDB = new AmazonDynamoDBClient(credentials);
		sns = new AmazonSNSClient(credentials);
		
		File tmpDir = new File(TMP_DIR_PATH);
		if(!tmpDir.isDirectory())
			throw new ServletException(TMP_DIR_PATH + " is not a directory");
		
		DiskFileItemFactory  fileItemFactory = new DiskFileItemFactory ();
		//Set the size threshold be 1MB, above which content will be stored on disk.
		fileItemFactory.setSizeThreshold(1*1024*1024);
		//Set the temporary directory to store the uploaded files of size above threshold.
		fileItemFactory.setRepository(tmpDir); 
		uploadHandler = new ServletFileUpload(fileItemFactory);
	}
	
	public void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		Map<String,String> parameterMap = new HashMap<String, String>();
		FileItem fileItem = null;
		try {
			//process form fields
			List<FileItem> items = (List<FileItem>)uploadHandler.parseRequest(req);
			for (FileItem item : items) {
				if (item.isFormField()) {
					//Process regular form field
					parameterMap.put(item.getFieldName(), item.getString());
				} else {
					//save the uploaded file temporarily
					//upload it to s3 later if required
					//assume only one file is uploaded
					fileItem = item;
				}
			}
		} catch (Exception e) {
			throw new ServletException("Cannot parse multipart request.", e);
		}
		
		String res = SUCCESS_EC;
		String command = parameterMap.get(COMMAND);
		
		if(command != null){
			String user = parameterMap.get(USER_NAME);
		    String psswd = parameterMap.get(PASSWORD);
		    //check if user/password is valid
			if(user == null || user.equals("") || psswd == null || psswd.equals("") ){
				res = FAILURE_EC + ",Empty username or password!";
			} else {
				switch (Integer.parseInt(command)) {
				case CMD_USER_REGISTER:
					res = createNewUser(user, psswd);
					break;
				case CMD_USER_SIGNIN:
					res = authenticateUser(user, psswd);
					if(res.equals(SUCCESS_EC)) {
						res = getSNSTopic(user);
					}
					break;
				case CMD_GET_VERSION:
					res = authenticateUser(user, psswd);
					if(res.equals(SUCCESS_EC)) {
						String files = parameterMap.get(FILES);
						res = getFileVersions(user, files);
					}
					break;
				case CMD_GET_URL:
					res = authenticateUser(user, psswd);
					if(res.equals(SUCCESS_EC)) {
						String files = parameterMap.get(FILES);
						res = getFileURLs(user, files);
					}
					break;
				case CMD_UPLOAD_FILE:
					res = authenticateUser(user, psswd);
					if(res.equals(SUCCESS_EC)) {
						String fullFileName = parameterMap.get(FILE_NAME);
						res = processUploadedFile(user,fullFileName,fileItem);
					}
					break;
				case CMD_DELETE_FILE:
					res = authenticateUser(user, psswd);
					if(res.equals(SUCCESS_EC)) {
						String files = parameterMap.get(FILES);
						res = deleteFiles(user,files);
					}
					break;
				case CMD_USER_DEREGISTER:
					res = authenticateUser(user, psswd);
					if(res.equals(SUCCESS_EC)) {
						res = deleteUser(user);
					}
					break;
				default: 
					res = FAILURE_EC + ",Invalid Command!";
					break;
				}
			}
		} else {			
			res = FAILURE_EC + ",Command is not given!";
		}
		
		resp.setContentType("text/plain");
		PrintWriter pw = resp.getWriter();
		pw.write(res);
	}
	
	/**
	 * get user info from database
	 * @param user
	 * @return
	 */
	private Map<String, AttributeValue> getUserInfo(String user){

		GetItemRequest getItemRequest = new GetItemRequest()
			.withTableName(userTableName)
			.withKey(new Key().withHashKeyElement(new AttributeValue().withS(user)));

		GetItemResult getItemResult = dynamoDB.getItem(getItemRequest);
		return getItemResult.getItem();
	}

	/**
	 * create a new user if it doesn't exist
	 * create an S3 bucket for this new user
	 * @param user
	 * @param psswd
	 * @return
	 */
	private String createNewUser(String user, String psswd){
		String res = SUCCESS_EC;
		
		//check if user already exists
		Map<String, AttributeValue> userInfo = getUserInfo(user);
        if (userInfo != null && userInfo.size() > 0){
        	res = FAILURE_EC + ",Username already exists!";
        	return res;
        }
        
        //create an S3 bucket for this new user
        String bucketName = "mydrive-" + user.toLowerCase();
        s3.createBucket(bucketName);
        
        //enable versioning for this bucket
        s3.setBucketVersioningConfiguration(
        		new SetBucketVersioningConfigurationRequest(bucketName,
        				new BucketVersioningConfiguration(BucketVersioningConfiguration.ENABLED)));
        
        //create a SNS topic for this bucket
        // Create a topic
        String topic = bucketName + "-topic";
        CreateTopicRequest createReq = new CreateTopicRequest().withName(topic);
        CreateTopicResult createRes = sns.createTopic(createReq);
        String topicArn = createRes.getTopicArn();
        
        //save user info to database
        Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
        item.put(USER_NAME, new AttributeValue().withS(user));
        item.put(PASSWORD, new AttributeValue().withS(psswd));
        item.put(S3_BUCKET_NAME, new AttributeValue().withS(bucketName));
        item.put(SNS_TOPIC, new AttributeValue().withS(topic));
        item.put(TOPIC_ARN, new AttributeValue().withS(topicArn));
        
        PutItemRequest putItemRequest = new PutItemRequest().withTableName(userTableName).withItem(item);
        dynamoDB.putItem(putItemRequest);
        item.clear();
        
        res = res + "," + topic;
        
		return res;
	}
	
	private String deleteUser(String user){
		String res = SUCCESS_EC;
		
		//delete all files of the user 
		res = deleteFiles(user, null);
		if(res.equals(SUCCESS_EC)){
			Map<String, AttributeValue> userInfo = getUserInfo(user);
			
			//delete the s3 bucket of the user
			String bucketName = ((AttributeValue)userInfo.get(S3_BUCKET_NAME)).getS();
			s3.deleteBucket(bucketName);
			
			//delete sns topic
			String topicArn = ((AttributeValue)userInfo.get(TOPIC_ARN)).getS();
			sns.deleteTopic(new DeleteTopicRequest().withTopicArn(topicArn));
			
			//delete user info from database
			DeleteItemRequest deleteItemRequest = new DeleteItemRequest()
					.withTableName(userTableName)
					.withKey(new Key().withHashKeyElement(new AttributeValue().withS(user)));;
			DeleteItemResult diRes = dynamoDB.deleteItem(deleteItemRequest);
		}
		return res;
	}
	
	/**
	 * validate if user has correct password
	 * @param user
	 * @param psswd
	 * @return
	 */
	private String authenticateUser(String user, String psswd){
        String res = SUCCESS_EC;	
		Map<String, AttributeValue> userInfo = getUserInfo(user);
		if(userInfo == null || userInfo.size() == 0){
			res = FAILURE_EC + ",User doesn't exist!";
		} else {
			if(!((AttributeValue)userInfo.get(PASSWORD)).getS().equals(psswd)){
				res = FAILURE_EC + ",Incorrect password!";
			}
		}
		return res;
	}
	
	/**
	 * get SNS topic from database
	 * @param user
	 * @return
	 */
	private String getSNSTopic(String user){
		String res = SUCCESS_EC;
		
		//user should exist, as authenticateUser() should be invoked before this method
	    Map<String, AttributeValue> userInfo = getUserInfo(user);
		String topic = ((AttributeValue)userInfo.get(SNS_TOPIC)).getS();
		
		res = res + "," + topic;
		return res;
	}
	
	/**
	 * get keys of given files
	 * get all keys of the bucket if no files is given
	 * @param bucketName
	 * @param files
	 * @return
	 */
	private List<String> getKeys(String bucketName, String files){
		List<String> keyList = new ArrayList<String>();
		if(files == null || files.trim().equals("")){
			//ask for all files
			ObjectListing objectList = null;
			do
			{	objectList = s3.listObjects(bucketName);
				List<S3ObjectSummary> summaries = objectList.getObjectSummaries();
				for(S3ObjectSummary summary : summaries){
					keyList.add(summary.getKey());
				}
			} while (objectList.isTruncated());
		} else {
			// ask for specified files
			String[] fileNames = files.trim().split(",");
			for(int i=0; i<fileNames.length; i++){
				keyList.add(fileNames[i].trim());
			}
		}
		return keyList;
	}
	
	/**
	 * get file versions from s3
	 * @param user
	 * @param files
	 * @return
	 */
	private String getFileVersions(String user, String files){
		String res = SUCCESS_EC;
		
		//user should exist, as authenticateUser() should be invoked before this method
		Map<String, AttributeValue> userInfo = getUserInfo(user);
		String bucketName = ((AttributeValue)userInfo.get(S3_BUCKET_NAME)).getS();
		
		//get all needed keys
		List<String> keyList = getKeys(bucketName, files);
		
		//get versions according to the keys above
		Map<String, String> versionMap = new HashMap<String, String>();
		for(String key : keyList){
			VersionListing versionList = null;
			do
			{	versionList = s3.listVersions(bucketName,key);
				List<S3VersionSummary> summaries = versionList.getVersionSummaries();
				for(S3VersionSummary summary : summaries){
					if(summary.isLatest() && !summary.isDeleteMarker()){
						versionMap.put(summary.getKey(), summary.getVersionId());
					}
				}
			} while (versionList.isTruncated());
		}
		
		StringBuffer sb = new StringBuffer(res);
		for(Map.Entry<String, String> e : versionMap.entrySet()){
			sb.append("," + e.getKey() + "::" + e.getValue());
		}
		
		return sb.toString();
	}
	
	/**
	 * get file URLs from s3
	 * @param user
	 * @param fileList
	 * @return
	 */
	private String getFileURLs(String user, String files){
		String res = SUCCESS_EC;
		
		//user should exist, as authenticateUser() should be invoked before this method
		Map<String, AttributeValue> userInfo = getUserInfo(user);		
		String bucketName = ((AttributeValue)userInfo.get(S3_BUCKET_NAME)).getS();
		
		//get all needed keys
		List<String> keyList = getKeys(bucketName, files);
		
		GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
		calendar.add(GregorianCalendar.HOUR, 1);
		Date expiration = calendar.getTime();
		
		StringBuffer sb = new StringBuffer(res);
		for(String key : keyList){
			String url = s3.generatePresignedUrl(bucketName, key, expiration).toString();
			sb.append("," + key + "::" + url);
		}
		
		return sb.toString();
	}
	
	/**
	 * send the uploaded file to s3
	 * @param user
	 * @param fullFileName
	 * @param fileItem
	 * @return
	 * @throws ServletException 
	 */
	private String processUploadedFile(String user, String fullFileName, FileItem fileItem) throws ServletException{
		String res = SUCCESS_EC;
		
		try{
			//user should exist, as authenticateUser() should be invoked before this method
			Map<String, AttributeValue> userInfo = getUserInfo(user);		
			String bucketName = ((AttributeValue)userInfo.get(S3_BUCKET_NAME)).getS();

			//String fileName = FilenameUtils.getName(fileItem.getName());
			File tempFile = File.createTempFile("temp", FilenameUtils.getExtension(fullFileName));
			tempFile.deleteOnExit();
			fileItem.write(tempFile);
			PutObjectRequest poReq = new PutObjectRequest(bucketName, fullFileName, tempFile);
			PutObjectResult poRes = s3.putObject(poReq);
			String version = poRes.getVersionId();
			res = res + "," + version;
			
			//create a URL
			GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
			calendar.add(GregorianCalendar.HOUR, 1);
			Date expiration = calendar.getTime();
			String url = s3.generatePresignedUrl(bucketName, fullFileName, expiration).toString();
			
			// Publish a message
			String topicArn = ((AttributeValue)userInfo.get(TOPIC_ARN)).getS();
            PublishRequest publishReq = new PublishRequest().withTopicArn(topicArn)
                .withMessage(FILE_UPLOADED + "," + fullFileName + "," + version + "," +url);
            sns.publish(publishReq);
		}
		catch(Exception e) {
			throw new ServletException("Error when processing uploaded file.", e);
		}		
		return res;
	}
	
	private String deleteFiles(String user, String files){
        String res = SUCCESS_EC;
		
		//user should exist, as authenticateUser() should be invoked before this method
		Map<String, AttributeValue> userInfo = getUserInfo(user);		
		String bucketName = ((AttributeValue)userInfo.get(S3_BUCKET_NAME)).getS();
		
		//get all needed keys
		List<String> keyList = getKeys(bucketName, files);
		
		//s3.deleteObjects(new DeleteObjectsRequest(bucketName).withKeys(keyList.toArray(new String[]{})));
		
		for(String key : keyList){
			VersionListing versionList = null;
			do
			{	versionList = s3.listVersions(bucketName,key);
				List<S3VersionSummary> summaries = versionList.getVersionSummaries();
				for(S3VersionSummary summary : summaries){
					s3.deleteVersion(bucketName, summary.getKey(), summary.getVersionId());
				}
			} while (versionList.isTruncated());
			
			// Publish to a topic
			String topicArn = ((AttributeValue)userInfo.get(TOPIC_ARN)).getS();
			PublishRequest publishReq = new PublishRequest().withTopicArn(topicArn)
			                .withMessage(FILE_DELETED + "," + key);
			sns.publish(publishReq);
		}
		
		return res;
	}
}
