<%@ page language="java" contentType="text/html; charset=utf-8" pageEncoding="utf-8"%>
<%@ page import="java.util.List" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="org.apache.commons.io.FilenameUtils" %>

<!DOCTYPE html>
<html>
<head>
    <meta http-equiv="Content-type" content="text/html; charset=utf-8">
    <title>MyDrive Web Client</title>
    <link rel="stylesheet" href="styles/styles.css" type="text/css" media="screen">
</head>

<body>
    <br/>
    
    <form name="userinput" action="/mydrive" enctype="multipart/form-data" method="post">
        User name:<input type="text" name="username"><br/>
        Password:<input type="password" name="password"><br/>
        Command:<input type="text" name="command"><br/>
        
        Files:<br/>
        <input type="text" name="files"><br/>
        
        Full path and file name:<br/>
        <input type="text" name="filename"><br/>
        <input type="file" name="uploadedfile"><br/>
		<input type="submit" value="Submit"><br/>
	</form>
    
</body>
</html>