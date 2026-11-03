```
/** Uploads a file through the Aprimo "Upload New > Upload files" menu without leaving a native file chooser open. */
public static void uploadFileWithoutChooser(String uploadedFilePath) throws InterruptedException {
    WebDriver driver = DriverFactory.getInstance().getDriver();
    JavascriptExecutor js = (JavascriptExecutor) driver;

    // open the "Upload New" menu
    driver.findElement(By.xpath("//button[@data-tracking='interaction:upload:upload-new']")).click();
    CommonFunctions.waitInMS(5);

    // block the native chooser, but mark the input the app tries to open
    js.executeScript(
        "if(!window.__origInputClick){window.__origInputClick=HTMLInputElement.prototype.click;}" +
        "HTMLInputElement.prototype.click=function(){" +
        "  if(this.type==='file'){" +
        "    this.setAttribute('data-test-file-input','1');" +
        "    if(!this.isConnected){this.style.display='none';document.body.appendChild(this);}" +
        "    return;" +
        "  }" +
        "  return window.__origInputClick.apply(this,arguments);" +
        "};");

    // "Upload files": the app sets up the upload and calls input.click(), which we swallow
    driver.findElement(By.xpath("//li[@data-tracking='interaction:upload:select-files']")).click();
    CommonFunctions.waitInMS(2);

    List<WebElement> marked = driver.findElements(By.cssSelector("input[data-test-file-input='1']"));
    WebElement fileInput = !marked.isEmpty() ? marked.get(0)
            : driver.findElement(By.cssSelector("body > input[type='file']"));
    fileInput.sendKeys(uploadedFilePath);

    // restore normal click behaviour
    js.executeScript("if(window.__origInputClick){HTMLInputElement.prototype.click=window.__origInputClick;}");
}

public static void uploadFileVersion() throws InterruptedException, IOException {
    //code for add new file version
    if (exec_container.equals("jenkins")) {
        CommonFunctions.setFileDetectorToJenkins();
    }

    String originalFilePath = System.getProperty("user.dir") + "/src/test/java/com/xyz/dam/utils/Images/yyz-Testing_test.pdf";
    String uploadedFilePath = /* your original line, unchanged */;

    try {
        Files.copy(Paths.get(originalFilePath), Paths.get(uploadedFilePath));
        Log.info("File copied to: " + uploadedFilePath);
    } catch (IOException e) {
        Log.error("Failed to create directory: " + e.getMessage());
    }
    Log.info("originalFilePath " + originalFilePath);
    Log.info("uploadedFilePath " + uploadedFilePath);

    uploadFileWithoutChooser(uploadedFilePath);

    Log.info("File Uploaded ");
    CommonFunctions.waitInMS(20);
}

```
