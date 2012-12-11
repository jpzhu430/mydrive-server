The "mydrive" application consists of following modules.

1> Server module: a Java Servelt supporting POST method.
   
   For each user, there is an S3 bucket for storing files, and 
   an DynamoDB entry for storing bookkeeping info, which are 
   created when a user registeres to the server.
   
   The DyanmoDB table is called "mydriveusers", and its key is
   "username".
   
   It should be created manually before running the program.
   
   As the user name will be part of the bucket name. So it has
   following restrictions.
   - should not contain underscores
   - should be between 3 and 55 characters long
   - should not end with a dash
   - cannot contain adjacent periods
   - cannot contain dashes next to periods (e.g., 
     "my-.bucket.com" and "my.-bucket" are invalid)
   - cannot contain uppercase characters
   
   The client will always upload files with the server API 
   instead of sending files to S3 directly. One reason is to 
   try to hide as much AWS API as possible from client. Another 
   is that the server should know when a file has been uploaded and
   then can publish message to SNS, which will send notification to
   the clients.
   
   The server will create an SNS topic for each bucket. The topic is
   returned when a user is registered or signed in. The client could
   use this topic name to create a topic and subscribe to it.
   
2> Local client on PC(or maybe Mac)

   The client should has a local bookkeepinging table for keeping 
   file versions, SNS topic name, current user name, current user password,
   a flag indicating if a file is modified but not uploaded to S3.
   
   When started up, it creates an "MyDrive" folder if it doesn't exist. 
   Then it starts to synchronize by sending request to the server, which 
   returns versions of all files for that user. The client compares those 
   versions with the local copies, and download the newer files from S3 with 
   the URL if versions are different, except when the modification flag is set.
   The URLs are obtained from server via separate requests.
   
   When a user updates a file, it sends the file to server which will updates to S3.
   If there is no network connections at that time, it just set the modification flag.
   When connection resumes, it will send the updated file to the server. 
   
   This flag should be used along with the file versions to distinguish different cases.
   
   There could be button for user to start synchronizing, when e.g., 
   the network connection is recovered.
   
   If the notification service is implmented with AWS SNS, the client doesn't need to
   periodically poll the server for udpates.

3> Web client (maybe optional)
      Similar functionality except it doesn't keep local file copies, 
      as it uses S3 links instead. Therefore, it has no synchronization issue.
      
Notification service implmentation

Likely we could use AWS SNS to implement the notification 
service for file changes. Here are some materials about SNS.

Link from Amazon:
http://docs.amazonwebservices.com/sns/latest/gsg/SendMessageToHttp.html

Sample code:
http://mfine.github.com/2010/06/15/simple-sns-http-receiver.html
https://github.com/mfine/AmazonSNSExample/blob/master/AmazonSNSReceiver.java

In our case, the server creates a topic for each user, and publish 
a message on that topic when user uploads a file via the server.
The client creates a same topic and subscribe to it. The client side 
also needs AWS credentials. But it could and should use the same credentials
with the server, which means we are using the same AWS account serving all
usrers at the background.

One thing to mention is that the client needs multi-threading, e.g., one 
for receiving notification, one for user interaction, one for monitoring 
the file folder and making necessary actions. 

The limitation is that SNS currently only supports 100 per account. But 
we use one AWS account to serve all the users. For additional topics, 
we need to contact amazon. However, I think it is OK for us to have this 
limit in the project as long as we address it, since it is not a product 
level program.

Interface between server and client

The client always sends HTTP request with POST method, using "multipart/form-data".
Request parameters consist of a command code and detailed request info.

HTTPClient:
http://hc.apache.org/httpcomponents-client-ga/index.html

Java URLConnection
http://stackoverflow.com/questions/2793150/how-to-use-java-net-urlconnection-to-fire-and-handle-http-requests

Example of uploading file using
http://www.servletworld.com/servlet-tutorials/servlet-file-upload-example.html

The command code is as below:
 "0" register,  "1" signin, "2" ask for file version, "3" ask for file url, 
 "4" upload file to server, "5" delete file, "6" de-register user  

The response from the server is in plain text, and fields are separated with comma",", 
as our program is quite simple. The client could just read the text and split it with ",". 

Use cases for our application:

1.register
  
  request to server: 
  "command"/"0","username"/"xxx","password"/"xxx"
  
  response from server: 
  "1,failure reason"
  "0,sns_topic"

2.sign-in

  request to server: 
  "command"/"1","username"/"xxx","password"/"xxx"
  
  response from server: 
  "1,failure reason"
  "0,sns-topic"

3.ask for file versions

  request to server: 
  "command"/"2","username"/"xxx","password"/"xxx","files"/"file1,file2,file3,..."
  
  file names include path(relative to "mydrive" folder), and are 
  concatenated with ",". 
  If asking versions for all files, use empty string "" as the file list.
  The client should pre-process the path, i.e., change "\" to "/".
  The file names returned from server will always use "/". Client should
  change it to "\" when in windows.
  
  response from server: 
  "1,failure reason"
  "0,file1:version,file2:version, ..."
  
  For each file, the name and version are separated with ":"

4.ask for file url

  request to server:  
  
  "command"/"3","username"/"xxx","password"/"xxx","files"/"file1,file2,file3,..."

  response from server:  
  "1,failure reason"
  "0,file1:url,file2:url,..."
   
  For each file, the name and url are separated with ":"

5.upload file

  request to server:  
  "command"/"4","username"/"xxx","password"/"xxx","filename"/"filename(including path)"

  response to client: 
  "1,failure reason"
  "0,version"

6.delete file

   request to server:
   "command"/"5","username"/"xxx","password"/"xxx","files"/"file1,file2,file3,..."
   When deleting all files, use empty sting "" in the file list
   
   response to clietn;
   "1,failure reason"
   "0"
   
 7.deregister user
 
   request to server:
   
   "command"/"6","username"/"xxx","password"/"xxx"
   
   response to client:
   
   response to clietn;
   "1,failure reason"
   "0"