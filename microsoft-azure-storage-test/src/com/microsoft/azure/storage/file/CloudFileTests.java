/**
 * Copyright Microsoft Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.microsoft.azure.storage.file;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.microsoft.azure.storage.NameValidator;
import com.microsoft.azure.storage.OperationContext;
import com.microsoft.azure.storage.RetryNoRetry;
import com.microsoft.azure.storage.SendingRequestEvent;
import com.microsoft.azure.storage.StorageErrorCodeStrings;
import com.microsoft.azure.storage.StorageEvent;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.TestRunners.CloudTests;
import com.microsoft.azure.storage.TestRunners.DevFabricTests;
import com.microsoft.azure.storage.TestRunners.DevStoreTests;
import com.microsoft.azure.storage.core.Base64;

/**
 * File Tests
 */
@Category(CloudTests.class)
public class CloudFileTests {
    protected CloudFileShare share;

    @Before
    public void fileTestMethodSetup() throws URISyntaxException, StorageException {
        this.share = FileTestHelper.getRandomShareReference();
        this.share.create();
    }

    @After
    public void fileTestMethodTearDown() throws StorageException {
        this.share.deleteIfExists();
    }

    /**
     * Test file name validation.
     */
    @Test
    public void CloudFileNameValidation()
    {
        NameValidator.validateFileName("alpha");
        NameValidator.validateFileName("4lphanum3r1c");
        NameValidator.validateFileName("middle-dash");
        NameValidator.validateFileName("CAPS");
        NameValidator.validateFileName("$root");

        invalidFileTestHelper(null, "No null.", "Invalid file name. The name may not be null, empty, or whitespace only.");
        invalidFileTestHelper("..", "Reserved.", "Invalid file name. This name is reserved.");
        invalidFileTestHelper("Clock$", "Reserved.", "Invalid file name. This name is reserved.");
        invalidFileTestHelper("endslash/", "No slashes.", "Invalid file name. Check MSDN for more information about valid naming.");
        invalidFileTestHelper("middle/slash", "No slashes.", "Invalid file name. Check MSDN for more information about valid naming.");
        invalidFileTestHelper("illegal\"char", "Illegal characters.", "Invalid file name. Check MSDN for more information about valid naming.");
        invalidFileTestHelper("illegal:char?", "Illegal characters.", "Invalid file name. Check MSDN for more information about valid naming.");
        invalidFileTestHelper("", "Between 1 and 255 characters.", "Invalid file name. The name may not be null, empty, or whitespace only.");
        invalidFileTestHelper(new String(new char[256]).replace("\0", "n"), "Between 1 and 255 characters.", "Invalid file name length. The name must be between 1 and 255 characters long.");
    }
    
    private void invalidFileTestHelper(String fileName, String failMessage, String exceptionMessage)
    {
        try
        {
            NameValidator.validateFileName(fileName);
            fail(failMessage);
        }
        catch (IllegalArgumentException e)
        {
            assertEquals(exceptionMessage, e.getMessage());
        }
    }
    
    /**
     * Test file creation and deletion.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileCreateAndDelete() throws URISyntaxException, StorageException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");
        file.create(0);
        assertTrue(file.exists());
        file.delete();
    }

    /**
     * Test file constructor.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileConstructor() throws URISyntaxException, StorageException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");
        CloudFile file2 = new CloudFile(file.getStorageUri(), null);
        assertEquals(file.getName(), file2.getName());
        assertEquals(file.getStorageUri(), file2.getStorageUri());
        assertEquals(file.getShare().getStorageUri(), file2.getShare().getStorageUri());
        assertEquals(file.getServiceClient().getStorageUri(), file2.getServiceClient().getStorageUri());

        CloudFile file3 = new CloudFile(file2);
        assertEquals(file3.getName(), file2.getName());
        assertEquals(file3.getStorageUri(), file2.getStorageUri());
        assertEquals(file3.getShare().getStorageUri(), file2.getShare().getStorageUri());
        assertEquals(file3.getServiceClient().getStorageUri(), file2.getServiceClient().getStorageUri());

    }

    /**
     * Test file resizing.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileResize() throws URISyntaxException, StorageException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");
        CloudFile file2 = this.share.getRootDirectoryReference().getFileReference("file1");

        file.create(1024);
        assertEquals(1024, file.getProperties().getLength());
        file2.downloadAttributes();
        assertEquals(1024, file2.getProperties().getLength());
        file2.getProperties().setContentType("text/plain");
        file2.uploadProperties();
        file.resize(2048);
        assertEquals(2048, file.getProperties().getLength());
        file.downloadAttributes();
        assertEquals("text/plain", file.getProperties().getContentType());
        file2.downloadAttributes();
        assertEquals(2048, file2.getProperties().getLength());

        // Resize to 0 length
        file.resize(0);
        assertEquals(0, file.getProperties().getLength());
        file.downloadAttributes();
        assertEquals("text/plain", file.getProperties().getContentType());
        file2.downloadAttributes();
        assertEquals(0, file2.getProperties().getLength());
    }

    /**
     * Test file creation with invalid sizes.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileCreateInvalidSize() throws StorageException, URISyntaxException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");

        try {
            file.create(-1);
            fail("Creating a file with size<0 should fail");
        }
        catch (StorageException e) {
            assertEquals(e.getHttpStatusCode(), 400);
            assertEquals(e.getMessage(), "The value for one of the HTTP headers is not in the correct format.");
        }

        try {
            file.create(1L * 1024 * 1024 * 1024 * 1024 + 1);
            fail("Creating a file with size>1TB should fail");
        }
        catch (StorageException e) {
            assertEquals(e.getHttpStatusCode(), 400);
            assertEquals(e.getMessage(), "The value for one of the HTTP headers is not in the correct format.");
        }
    }

    /**
     * Test file deleteIfExists.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileDeleteIfExists() throws URISyntaxException, StorageException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");
        assertFalse(file.deleteIfExists());
        file.create(0);
        assertTrue(file.deleteIfExists());
        assertFalse(file.deleteIfExists());

    }

    /**
     * Test file exits method.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileExists() throws URISyntaxException, StorageException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");
        CloudFile file2 = this.share.getRootDirectoryReference().getFileReference("file1");

        assertFalse(file2.exists());

        file.create(2048);

        assertTrue(file2.exists());
        assertEquals(2048, file2.getProperties().getLength());

        file.delete();

        assertFalse(file2.exists());
    }

    /**
     * Test file getProperties.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileDownloadAttributes() throws URISyntaxException, StorageException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file29");
        file.create(1024);
        assertEquals(1024, file.getProperties().getLength());
        assertNotNull(file.getProperties().getEtag());
        GregorianCalendar now = new GregorianCalendar();
        now.add(GregorianCalendar.MINUTE, 5);
        assertTrue(file.getProperties().getLastModified().before(now.getTime()));
        assertNull(file.getProperties().getCacheControl());
        assertNull(file.getProperties().getContentDisposition());
        assertNull(file.getProperties().getContentEncoding());
        assertNull(file.getProperties().getContentLanguage());
        assertNull(file.getProperties().getContentType());
        assertNull(file.getProperties().getContentMD5());

        CloudFile file2 = this.share.getRootDirectoryReference().getFileReference("file29");
        file2.downloadAttributes();
        assertEquals(1024, file2.getProperties().getLength());
        assertEquals(file.getProperties().getEtag(), file2.getProperties().getEtag());
        assertEquals(file.getProperties().getLastModified(), file2.getProperties().getLastModified());
        assertNull(file2.getProperties().getCacheControl());
        assertNull(file2.getProperties().getContentDisposition());
        assertNull(file2.getProperties().getContentEncoding());
        assertNull(file2.getProperties().getContentLanguage());
        assertEquals("application/octet-stream", file2.getProperties().getContentType());
        assertNull(file2.getProperties().getContentMD5());
    }

    /**
     * Test file setProperties.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileSetProperties() throws StorageException, URISyntaxException, InterruptedException {

        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");
        file.create(1024);
        String eTag = file.getProperties().getEtag();
        Date lastModified = file.getProperties().getLastModified();

        Thread.sleep(1000);

        file.getProperties().setCacheControl("no-transform");
        file.getProperties().setContentDisposition("attachment");
        file.getProperties().setContentEncoding("gzip");
        file.getProperties().setContentLanguage("tr,en");
        file.getProperties().setContentMD5("MDAwMDAwMDA=");
        file.getProperties().setContentType("text/html");
        file.uploadProperties();
        assertTrue(file.getProperties().getLastModified().after(lastModified));
        assertTrue(!eTag.equals(file.getProperties().getEtag()));

        CloudFile file2 = this.share.getRootDirectoryReference().getFileReference("file1");
        file2.downloadAttributes();
        assertEquals("no-transform", file2.getProperties().getCacheControl());
        assertEquals("attachment", file2.getProperties().getContentDisposition());
        assertEquals("gzip", file2.getProperties().getContentEncoding());
        assertEquals("tr,en", file2.getProperties().getContentLanguage());
        assertEquals("MDAwMDAwMDA=", file2.getProperties().getContentMD5());
        assertEquals("text/html", file2.getProperties().getContentType());

        CloudFile file3 = this.share.getRootDirectoryReference().getFileReference("file1");
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        FileRequestOptions options = new FileRequestOptions();
        options.setDisableContentMD5Validation(true);
        file3.download(stream, null, options, null);
        FileTestHelper.assertAreEqual(file2.getProperties(), file3.getProperties());

        CloudFileDirectory rootDirectory = this.share.getRootDirectoryReference();
        Iterator<ListFileItem> iter = rootDirectory.listFilesAndDirectories().iterator();
        CloudFile file4 = null;
        if (iter.hasNext()) {
            file4 = (CloudFile) iter.next();
        }
        else {
            fail("Expecting a file here.");
        }
        assertEquals(file2.getProperties().getLength(), file4.getProperties().getLength());
    }

    /**
     * Test file creation with metadata.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileCreateWithMetadata() throws URISyntaxException, StorageException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");
        HashMap<String, String> meta = new HashMap<String, String>();
        meta.put("key1", "value1");
        file.setMetadata(meta);
        file.create(1024);

        CloudFile file2 = this.share.getRootDirectoryReference().getFileReference("file1");
        file2.downloadAttributes();
        assertEquals(1, file2.getMetadata().size());
        assertEquals("value1", file2.getMetadata().get("key1"));
        file2.setMetadata(new HashMap<String, String>());
        file2.uploadMetadata();

        file.downloadAttributes();
        assertEquals(0, file.getMetadata().size());
    }

    /**
     * Test file stream uploading.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testFileUploadFromStreamTest1() throws URISyntaxException, StorageException, IOException {
        final String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile fileRef = this.share.getRootDirectoryReference().getFileReference(fileName);

        int length = 2 * 1024;
        ByteArrayInputStream srcStream = FileTestHelper.getRandomDataStream(length);
        fileRef.upload(srcStream, length);
        ByteArrayOutputStream dstStream = new ByteArrayOutputStream();
        fileRef.download(dstStream);
        FileTestHelper.assertStreamsAreEqual(srcStream, new ByteArrayInputStream(dstStream.toByteArray()));

        length = 5 * 1024 * 1024;
        srcStream = FileTestHelper.getRandomDataStream(length);
        fileRef.upload(srcStream, length);
        dstStream = new ByteArrayOutputStream();
        fileRef.download(dstStream);
        FileTestHelper.assertStreamsAreEqual(srcStream, new ByteArrayInputStream(dstStream.toByteArray()));
    }

    /**
     * Create a file and try to download a range of its contents
     * 
     * @throws StorageException
     * @throws URISyntaxException
     * @throws IOException
     * @throws InterruptedException
     */
    @Test
    public void testFileDownloadRangeValidationTest() throws StorageException, URISyntaxException, IOException {
        final int length = 5 * 1024 * 1024;

        final String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile fileRef = this.share.getRootDirectoryReference().getFileReference(fileName);

        fileRef.upload(FileTestHelper.getRandomDataStream(length), length);

        //Download full file
        fileRef.download(new ByteArrayOutputStream());
        assertEquals(length, fileRef.getProperties().getLength());

        //Download file range.
        byte[] downloadBuffer = new byte[100];
        int downloadLength = fileRef.downloadRangeToByteArray(0, (long) 100, downloadBuffer, 0);
        assertEquals(length, fileRef.getProperties().getLength());
        assertEquals(100, downloadLength);
    }

    @Test
    public void testFileUploadFromStreamTest() throws URISyntaxException, StorageException, IOException {
        final String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile fileRef = this.share.getRootDirectoryReference().getFileReference(fileName);

        int length = 2 * 1024;
        ByteArrayInputStream srcStream = FileTestHelper.getRandomDataStream(length);
        fileRef.upload(srcStream, length);
        ByteArrayOutputStream dstStream = new ByteArrayOutputStream();
        fileRef.download(dstStream);
        FileTestHelper.assertStreamsAreEqual(srcStream, new ByteArrayInputStream(dstStream.toByteArray()));

        length = 5 * 1024 * 1024;
        srcStream = FileTestHelper.getRandomDataStream(length);
        fileRef.upload(srcStream, length);
        dstStream = new ByteArrayOutputStream();
        fileRef.download(dstStream);
        FileTestHelper.assertStreamsAreEqual(srcStream, new ByteArrayInputStream(dstStream.toByteArray()));
    }

    @Test
    public void testFileUploadMD5Validation() throws URISyntaxException, StorageException, IOException,
            NoSuchAlgorithmException {
        final String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile fileRef = this.share.getRootDirectoryReference().getFileReference(fileName);

        final int length = 4 * 1024;

        byte[] src = FileTestHelper.getRandomBuffer(length);
        ByteArrayInputStream srcStream = new ByteArrayInputStream(src);
        FileRequestOptions options = new FileRequestOptions();
        options.setDisableContentMD5Validation(false);
        options.setStoreFileContentMD5(false);

        fileRef.upload(srcStream, length, null, options, null);
        fileRef.downloadAttributes();
        fileRef.getProperties().setContentMD5("MDAwMDAwMDA=");
        fileRef.uploadProperties(null, options, null);

        try {
            fileRef.download(new ByteArrayOutputStream(), null, options, null);
            fail();
        }
        catch (StorageException ex) {
            assertEquals(306, ex.getHttpStatusCode());
            assertEquals("InvalidMd5", ex.getErrorCode());
        }

        options.setDisableContentMD5Validation(true);
        fileRef.download(new ByteArrayOutputStream(), null, options, null);

        options.setDisableContentMD5Validation(false);
        final MessageDigest digest = MessageDigest.getInstance("MD5");
        final String calculatedMD5 = Base64.encode(digest.digest(src));

        fileRef.downloadAttributes();
        fileRef.getProperties().setContentMD5(calculatedMD5);
        fileRef.uploadProperties(null, options, null);
        fileRef.download(new ByteArrayOutputStream(), null, options, null);
        fileRef.downloadToByteArray(new byte[4096], 0);

        FileInputStream stream = fileRef.openRead();
        stream.mark(length);
        stream.read(new byte[4096]);
        try {
            HashMap<String, String> metadata = new HashMap<String, String>();
            metadata.put("a", "value");
            fileRef.setMetadata(metadata);
            fileRef.uploadMetadata();
            stream.reset();
            stream.read(new byte[4096]);
        }
        catch (IOException e) {
            assertEquals(e.getCause().getMessage(),
                    "The conditionals specified for this operation did not match server.");
        }
    }

    @Test
    public void testFileEmptyHeaderSigningTest() throws URISyntaxException, StorageException, IOException {
        final String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile fileRef = this.share.getRootDirectoryReference().getFileReference(fileName);

        final int length = 2 * 1024;
        ByteArrayInputStream srcStream = FileTestHelper.getRandomDataStream(length);

        OperationContext context = new OperationContext();
        context.getSendingRequestEventHandler().addListener(new StorageEvent<SendingRequestEvent>() {

            @Override
            public void eventOccurred(SendingRequestEvent eventArg) {
                HttpURLConnection connection = (HttpURLConnection) eventArg.getConnectionObject();
                connection.setRequestProperty("x-ms-foo", "");
            }
        });

        try {
            fileRef.upload(srcStream, length, null, null, context);
            fileRef.download(new ByteArrayOutputStream(), null, null, context);
            fail("Shouldn't sign empty header, expected a 403.");
        }
        catch (StorageException e) {
            assertEquals(e.getHttpStatusCode(), 403);
            assertEquals(
                    e.getMessage(),
                    "Server failed to authenticate the request. Make sure the value of Authorization header is formed correctly including the signature.");
        }
    }

    /**
     * Test downloading a file range.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testFileDownloadRangeTest() throws URISyntaxException, StorageException, IOException {
        byte[] buffer = FileTestHelper.getRandomBuffer(2 * 1024);

        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");
        ByteArrayInputStream wholeFile = new ByteArrayInputStream(buffer);
        file.upload(wholeFile, 2 * 1024);

        ByteArrayOutputStream fileStream = new ByteArrayOutputStream();
        try {
            file.downloadRange(0, new Long(0), fileStream);
        }
        catch (IndexOutOfBoundsException ex) {

        }

        file.downloadRange(0, new Long(1024), fileStream);
        assertEquals(fileStream.size(), 1024);
        FileTestHelper.assertStreamsAreEqualAtIndex(new ByteArrayInputStream(fileStream.toByteArray()), wholeFile, 0,
                0, 1024, 2 * 1024);

        CloudFile file2 = this.share.getRootDirectoryReference().getFileReference("file1");
        try {
            file.downloadRange(1024, new Long(0), fileStream);
        }
        catch (IndexOutOfBoundsException ex) {

        }

        ByteArrayOutputStream fileStream2 = new ByteArrayOutputStream();
        file2.downloadRange(1024, new Long(1024), fileStream2);
        FileTestHelper.assertStreamsAreEqualAtIndex(new ByteArrayInputStream(fileStream2.toByteArray()), wholeFile,
                1024, 1024, 1024, 2 * 1024);

        FileTestHelper.assertAreEqual(file, file2);
    }

    @Test
    public void testCloudFileDownloadToByteArray() throws URISyntaxException, StorageException, IOException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");
        FileTestHelper.doDownloadTest(file, 1 * 512, 2 * 512, 0);
        FileTestHelper.doDownloadTest(file, 1 * 512, 2 * 512, 1 * 512);
        FileTestHelper.doDownloadTest(file, 2 * 512, 4 * 512, 1 * 512);
        FileTestHelper.doDownloadTest(file, 5 * 1024 * 1024, 5 * 1024 * 1024, 0);
        FileTestHelper.doDownloadTest(file, 5 * 1024 * 1024, 6 * 1024 * 1024, 512);
    }

    @Test
    public void testCloudFileDownloadRangeToByteArray() throws URISyntaxException, StorageException, IOException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference(
                FileTestHelper.generateRandomFileName());

        FileTestHelper.doDownloadRangeToByteArrayTest(file, 8 * 1024 * 1024, 8 * 1024 * 1024, 1 * 1024 * 1024,
                new Long(1 * 1024 * 1024), new Long(5 * 1024 * 1024));
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 8 * 1024 * 1024, 8 * 1024 * 1024, 2 * 1024 * 1024,
                new Long(2 * 1024 * 1024), new Long(6 * 1024 * 1024));
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 8 * 1024 * 1024, 8 * 1024 * 1024, 1 * 1024 * 1024,
                new Long(4 * 1024 * 1024), new Long(4 * 1024 * 1024));

        FileTestHelper.doDownloadRangeToByteArrayTest(file, 2 * 512, 4 * 512, 0, new Long(1 * 512), new Long(1 * 512));
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 2 * 512, 4 * 512, 1 * 512, new Long(0), null);
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 2 * 512, 4 * 512, 1 * 512, new Long(1 * 512), null);
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 2 * 512, 4 * 512, 1 * 512, new Long(0), new Long(1 * 512));
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 2 * 512, 4 * 512, 2 * 512, new Long(1 * 512), new Long(
                1 * 512));
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 2 * 512, 4 * 512, 2 * 512, new Long(1 * 512), new Long(
                2 * 512));

        // Edge cases
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 1024, 1024, 1023, new Long(1023), new Long(1));
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 1024, 1024, 0, new Long(1023), new Long(1));
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 1024, 1024, 0, new Long(0), new Long(1));
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 1024, 1024, 0, new Long(512), new Long(1));
        FileTestHelper.doDownloadRangeToByteArrayTest(file, 1024, 1024, 512, new Long(1023), new Long(1));
    }

    @Test
    public void testCloudFileDownloadRangeToByteArrayNegativeTest() throws URISyntaxException, StorageException,
            IOException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference(
                FileTestHelper.generateRandomFileName());
        FileTestHelper.doDownloadRangeToByteArrayNegativeTests(file);
    }

    /*
    @Test
    public void testCloudFileUploadFromStreamWithAccessCondition() throws URISyntaxException, StorageException,
            IOException {
        CloudFile file1 = share.getRootDirectoryReference().getFileReference("file1");
        AccessCondition accessCondition = AccessCondition.generateIfNoneMatchCondition("\"*\"");
        final int length = 6 * 512;
        ByteArrayInputStream srcStream = FileTestHelper.getRandomDataStream(length);
        file1.upload(srcStream, length, accessCondition, null, null);

        srcStream.reset();
        file1.create(1024);
        accessCondition = AccessCondition.generateIfNoneMatchCondition(file1.getProperties().getEtag());
        try {
            file1.upload(srcStream, length, accessCondition, null, null);
        }
        catch (StorageException ex) {
            Assert.assertEquals(HttpURLConnection.HTTP_PRECON_FAILED, ex.getHttpStatusCode());
        }

        srcStream.reset();
        accessCondition = AccessCondition.generateIfMatchCondition(file1.getProperties().getEtag());
        file1.upload(srcStream, length, accessCondition, null, null);

        srcStream.reset();
        CloudFile file2 = share.getRootDirectoryReference().getFileReference("file2");
        file2.create(1024);
        accessCondition = AccessCondition.generateIfMatchCondition(file1.getProperties().getEtag());
        try {
            file1.upload(srcStream, length, accessCondition, null, null);
        }
        catch (StorageException ex) {
            Assert.assertEquals(HttpURLConnection.HTTP_PRECON_FAILED, ex.getHttpStatusCode());
        }

        srcStream.reset();
        accessCondition = AccessCondition.generateIfNoneMatchCondition(file2.getProperties().getEtag());
        file1.upload(srcStream, length, accessCondition, null, null);
    }
    */

    /**
     * Test file input stream.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     * @throws IOException
     */
    @Test
    public void testCloudFileInputStream() throws URISyntaxException, StorageException, IOException {
        final int fileLength = 16 * 1024;
        final Random randGenerator = new Random();
        String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile fileRef = this.share.getRootDirectoryReference().getFileReference(fileName);

        final byte[] buff = new byte[fileLength];
        randGenerator.nextBytes(buff);
        buff[0] = -1;
        buff[1] = -128;
        final ByteArrayInputStream sourceStream = new ByteArrayInputStream(buff);

        final FileRequestOptions options = new FileRequestOptions();
        final OperationContext operationContext = new OperationContext();
        options.setTimeoutIntervalInMs(90000);
        options.setRetryPolicyFactory(new RetryNoRetry());
        fileRef.upload(sourceStream, fileLength, null, options, operationContext);

        com.microsoft.azure.storage.file.FileInputStream fileStream = fileRef.openRead();

        for (int i = 0; i < fileLength; i++) {
            int data = fileStream.read();
            assertTrue(data >= 0);
            assertEquals(buff[i], (byte) data);
        }

        assertEquals(-1, fileStream.read());

        fileRef.delete();
    }

    /**
     * Test file uploading from byte arrays.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileUploadFromByteArray() throws Exception {
        String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile file = this.share.getRootDirectoryReference().getFileReference(fileName);

        this.doUploadFromByteArrayTest(file, 4 * 512, 0, 4 * 512);
        this.doUploadFromByteArrayTest(file, 4 * 512, 0, 2 * 512);
        this.doUploadFromByteArrayTest(file, 4 * 512, 1 * 512, 2 * 512);
        this.doUploadFromByteArrayTest(file, 4 * 512, 2 * 512, 2 * 512);
    }

    /**
     * Test file upload and download using text.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileUploadDownloadFromText() throws IOException, StorageException, URISyntaxException {
        String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile file = this.share.getRootDirectoryReference().getFileReference(fileName);
        String textFile = "string of text to upload to a file. The_quick_brown_fox_jumps_over_the_lazy_dog. 1!\". &quot.";
        file.uploadText(textFile);
        String result = file.downloadText();
        assertEquals(textFile, result);
    }

    /**
     * Test file upload and download using io files.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileUploadDownloadFromFile() throws IOException, StorageException, URISyntaxException {
        String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile file = this.share.getRootDirectoryReference().getFileReference(fileName);
        File ioFile = new File("garbage.file");

        try {
            assertFalse(ioFile.exists());
            file.downloadToFile("garbage.file");
            fail("Shouldn't be able to download nonexistent file.");
        }
        catch (StorageException e) {
            assertFalse(ioFile.exists());
        }

        this.doUploadDownloadFileTest(file, 512);
        this.doUploadDownloadFileTest(file, 4096);
        this.doUploadDownloadFileTest(file, 5 * 1024 * 1024);
        this.doUploadDownloadFileTest(file, 11 * 1024 * 1024);
    }

    private void doUploadFromByteArrayTest(CloudFile file, int bufferSize, int bufferOffset, int count)
            throws Exception {
        byte[] buffer = FileTestHelper.getRandomBuffer(bufferSize);
        byte[] downloadedBuffer = new byte[bufferSize];

        file.uploadFromByteArray(buffer, bufferOffset, count);
        file.downloadToByteArray(downloadedBuffer, 0);

        int i = 0;
        for (; i < count; i++) {
            assertEquals(buffer[i + bufferOffset], downloadedBuffer[i]);
        }

        for (; i < downloadedBuffer.length; i++) {
            assertEquals(0, downloadedBuffer[i]);
        }
    }

    private void doUploadDownloadFileTest(CloudFile file, int fileSize) throws IOException, StorageException {
        File sourceFile = File.createTempFile("sourceFile", ".tmp");
        File destinationFile = new File(sourceFile.getParentFile(), "destinationFile.tmp");

        try {
            byte[] buffer = FileTestHelper.getRandomBuffer(fileSize);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(sourceFile);
            fos.write(buffer);
            fos.close();
            file.uploadFromFile(sourceFile.getAbsolutePath());

            file.downloadToFile(destinationFile.getAbsolutePath());
            assertTrue("Destination file does not exist.", destinationFile.exists());
            assertEquals("Destination file does not match input file.", fileSize, destinationFile.length());
            java.io.FileInputStream fis = new java.io.FileInputStream(destinationFile);

            byte[] readBuffer = new byte[fileSize];
            fis.read(readBuffer);
            fis.close();

            for (int i = 0; i < fileSize; i++) {
                assertEquals("File contents do not match.", buffer[i], readBuffer[i]);
            }
        }
        finally {
            if (sourceFile.exists()) {
                sourceFile.delete();
            }

            if (destinationFile.exists()) {
                destinationFile.delete();
            }
        }
    }

    /**
     * Test file range uploads.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileUploadRange() throws URISyntaxException, StorageException, IOException {
        int fileLengthToUse = 8 * 512;
        byte[] buffer = FileTestHelper.getRandomBuffer(8 * 512);

        String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile fileRef = this.share.getRootDirectoryReference().getFileReference(fileName);
        fileRef.create(fileLengthToUse);

        FileRequestOptions options = new FileRequestOptions();
        options.setUseTransactionalContentMD5(true);

        // Upload one page (page 0)
        ByteArrayInputStream inputStream = new ByteArrayInputStream(buffer);
        fileRef.uploadRange(inputStream, 0, 512, null, options, null);

        // Upload pages 2-4
        inputStream = new ByteArrayInputStream(buffer, 512, 3 * 512);
        fileRef.uploadRange(inputStream, 2 * 512, 3 * 512, null, options, null);

        // Now, we expect the first 512 bytes of the file to be the first 512 bytes of the random buffer (page 0)
        // the next 512 bytes should be 0 (page 1)
        // The next 3 * 512 bytes should be equal to bytes (512 -> 4 * 512) of the random buffer (pages 2-4)
        // The next 3 * 512 bytes should be 0 (pages 5-7)

        byte[] result = new byte[fileLengthToUse];
        fileRef.downloadToByteArray(result, 0);

        for (int i = 0; i < 512; i++) {
            assertEquals(buffer[i], result[i]);
        }

        for (int i = 0; i < 512; i++) {
            assertEquals(0, result[i + 512]);
        }

        for (int i = 0; i < 3 * 512; i++) {
            assertEquals(buffer[i + 512], result[i + 2 * 512]);
        }

        for (int i = 0; i < 3 * 512; i++) {
            assertEquals(0, result[i + 5 * 512]);
        }

        inputStream = new ByteArrayInputStream(buffer);
    }

    /**
     * Test clearing file ranges.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileClearRange() throws URISyntaxException, StorageException, IOException {
        int fileLengthToUse = 8 * 512;
        byte[] buffer = FileTestHelper.getRandomBuffer(8 * 512);

        String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile fileRef = this.share.getRootDirectoryReference().getFileReference(fileName);
        fileRef.create(fileLengthToUse);

        // Upload one page (page 0)
        ByteArrayInputStream inputStream = new ByteArrayInputStream(buffer);
        fileRef.uploadRange(inputStream, 0, fileLengthToUse);

        fileRef.clearRange(3 * 512, 2 * 512);

        byte[] result = new byte[fileLengthToUse];
        fileRef.downloadToByteArray(result, 0);

        int i = 0;

        for (; i < 3 * 512; i++) {
            assertEquals(buffer[i], result[i]);
        }

        for (; i < 5 * 512; i++) {
            assertEquals(0, result[i]);
        }

        for (; i < 8 * 512; i++) {
            assertEquals(buffer[i], result[i]);
        }
    }

    /**
     * Test file resizing.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileResize2() throws StorageException, URISyntaxException {
        CloudFile file = this.share.getRootDirectoryReference().getFileReference("file1");
        CloudFile file2 = this.share.getRootDirectoryReference().getFileReference("file1");

        file.create(1024);
        assertEquals(1024, file.getProperties().getLength());

        file2.downloadAttributes();
        assertEquals(1024, file2.getProperties().getLength());

        file2.getProperties().setContentType("text/plain");
        file2.uploadProperties();

        file.resize(2048);
        assertEquals(2048, file.getProperties().getLength());

        file.downloadAttributes();
        assertEquals("text/plain", file.getProperties().getContentType());

        file2.downloadAttributes();
        assertEquals(2048, file2.getProperties().getLength());
    }

    /**
     * Test file range downloading.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileDownloadRange() throws StorageException, URISyntaxException, IOException {
        int fileLengthToUse = 8 * 512;
        byte[] buffer = FileTestHelper.getRandomBuffer(8 * 512);

        String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile fileRef = this.share.getRootDirectoryReference().getFileReference(fileName);
        fileRef.create(fileLengthToUse);

        // Upload one page (page 0)
        ByteArrayInputStream inputStream = new ByteArrayInputStream(buffer);
        fileRef.uploadRange(inputStream, 0, 512);

        // Upload pages 2-4
        inputStream = new ByteArrayInputStream(buffer, 512, 3 * 512);
        fileRef.uploadRange(inputStream, 2 * 512, 3 * 512);

        // Now, we expect the first 512 bytes of the file to be the first 512 bytes of the random buffer (page 0)
        // the next 512 bytes should be 0 (page 1)
        // The next 3 * 512 bytes should be equal to bytes (512 -> 4 * 512) of the random buffer (pages 2-4)
        // The next 3 * 512 bytes should be 0 (pages 5-7)

        ArrayList<FileRange> actualFileRanges = fileRef.downloadFileRanges();
        ArrayList<FileRange> expectedFileRanges = new ArrayList<FileRange>();
        expectedFileRanges.add(new FileRange(0, 512 - 1));
        expectedFileRanges.add(new FileRange(2 * 512, 2 * 512 + 3 * 512 - 1));

        assertEquals(expectedFileRanges.size(), actualFileRanges.size());
        for (int i = 0; i < expectedFileRanges.size(); i++) {
            assertEquals(expectedFileRanges.get(i).getStartOffset(), actualFileRanges.get(i).getStartOffset());
            assertEquals(expectedFileRanges.get(i).getEndOffset(), actualFileRanges.get(i).getEndOffset());
        }
    }

    /**
     * Test downloadAttributes.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileUploadDownloadFileProperties() throws URISyntaxException, StorageException, IOException {
        final int length = 512;

        // do this to make sure the set MD5 can be compared without an exception being thrown
        FileRequestOptions options = new FileRequestOptions();
        options.setDisableContentMD5Validation(true);

        // with explicit upload/download of properties 
        String fileName1 = FileTestHelper.generateRandomFileName();
        CloudFile fileRef1 = this.share.getRootDirectoryReference().getFileReference(fileName1);

        fileRef1.upload(FileTestHelper.getRandomDataStream(length), length);

        FileTestHelper.setFileProperties(fileRef1);
        FileProperties props1 = fileRef1.getProperties();
        fileRef1.uploadProperties();

        fileRef1.downloadAttributes(null, options, null);
        FileProperties props2 = fileRef1.getProperties();

        FileTestHelper.assertAreEqual(props1, props2);

        // by uploading/downloading the file   
        fileName1 = FileTestHelper.generateRandomFileName();
        fileRef1 = this.share.getRootDirectoryReference().getFileReference(fileName1);

        FileTestHelper.setFileProperties(fileRef1);
        props1 = fileRef1.getProperties();

        fileRef1.upload(FileTestHelper.getRandomDataStream(length), length);

        fileRef1.download(new ByteArrayOutputStream(), null, options, null);
        props2 = fileRef1.getProperties();

        FileTestHelper.assertAreEqual(props1, props2);
    }

    /**
     * Test FileOutputStream.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileOpenOutputStream() throws URISyntaxException, StorageException, IOException {
        int fileLengthToUse = 8 * 512;
        byte[] buffer = FileTestHelper.getRandomBuffer(8 * 512);

        String fileName = FileTestHelper.generateRandomFileName();
        final CloudFile fileRef = this.share.getRootDirectoryReference().getFileReference(fileName);
        fileRef.create(fileLengthToUse);

        FileOutputStream fileOutputStream = fileRef.openWriteNew(fileLengthToUse);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(buffer);

        fileOutputStream = fileRef.openWriteNew(fileLengthToUse);
        inputStream = new ByteArrayInputStream(buffer);
        fileOutputStream.write(inputStream, 512);

        inputStream = new ByteArrayInputStream(buffer, 512, 3 * 512);
        fileOutputStream.write(inputStream, 3 * 512);

        fileOutputStream.close();

        byte[] result = new byte[fileLengthToUse];
        fileRef.downloadToByteArray(result, 0);

        int i = 0;
        for (; i < 4 * 512; i++) {
            assertEquals(buffer[i], result[i]);
        }

        for (; i < 8 * 512; i++) {
            assertEquals(0, result[i]);
        }
    }

    /**
     * Test FileOutputStream.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     */
    @Test
    public void testCloudFileOpenOutputStreamNoArgs() throws URISyntaxException, StorageException {
        String fileName = FileTestHelper.generateRandomFileName();
        CloudFile file = this.share.getRootDirectoryReference().getFileReference(fileName);

        try {
            file.openWriteExisting();
        }
        catch (StorageException ex) {
            assertEquals("The specified resource does not exist.", ex.getMessage());
            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, ex.getHttpStatusCode());
        }

        file.openWriteNew(1024);
        file.openWriteExisting();

        CloudFile file2 = this.share.getRootDirectoryReference().getFileReference(fileName);
        file2.downloadAttributes();
        assertEquals(1024, file2.getProperties().getLength());
    }

    /**
     * Test specific deleteIfExists case.
     * 
     * @throws URISyntaxException
     * @throws StorageException
     * @throws IOException
     */
    @Test
    @Category({ DevFabricTests.class, DevStoreTests.class })
    public void CloudFileDeleteIfExistsErrorCode() throws URISyntaxException, StorageException, IOException {
        CloudFileClient client = FileTestHelper.createCloudFileClient();
        CloudFileShare share = client.getShareReference(FileTestHelper.generateRandomShareName());
        share.create();
        CloudFileDirectory directory = share.getRootDirectoryReference().getSubDirectoryReference("directory");
        directory.create();
        final CloudFile file = directory.getFileReference("file");

        try {
            file.delete();
            fail("File should not already exist.");
        }
        catch (StorageException e) {
            assertEquals(StorageErrorCodeStrings.RESOURCE_NOT_FOUND, e.getErrorCode());
        }

        assertFalse(file.exists());
        assertFalse(file.deleteIfExists());

        file.create(2);
        assertTrue(file.exists());

        assertTrue(file.deleteIfExists());
        assertFalse(file.deleteIfExists());

        // check if second condition works in delete if exists
        OperationContext ctx = new OperationContext();
        ctx.getSendingRequestEventHandler().addListener(new StorageEvent<SendingRequestEvent>() {

            @Override
            public void eventOccurred(SendingRequestEvent eventArg) {
                if (((HttpURLConnection) eventArg.getConnectionObject()).getRequestMethod().equals("DELETE")) {
                    try {
                        file.delete();
                        assertFalse(file.exists());
                    }
                    catch (StorageException e) {
                        fail("Delete should succeed.");
                    }
                }
            }
        });

        // The second delete of a file will return a 404
        file.create(2);
        assertFalse(file.deleteIfExists(null, null, ctx));
    }
}