package burp;

import java.util.List;
import java.util.ArrayList;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;

import burp.Ui.Tags;
import burp.DnsLogModule.DnsLog;
import burp.Bootstrap.YamlReader;
import burp.Bootstrap.CustomBurpUrl;
import burp.Bootstrap.BurpAnalyzedRequest;
import burp.CustomErrorException.TaskTimeoutException;
import burp.Application.RemoteCmdExtension.RemoteCmd;

public class BurpExtender implements IBurpExtender, IScannerCheck {
    public static String NAME = "log4j2Scan_modify";
    public static String VERSION = "1.4.0";
    public static String COMMIT = "https://github.com/pmiaowu/log4j2Scan/commit/d160bf2349cb2f423300469a48de53c75a8031ec";

    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;

    private PrintWriter stdout;
    private PrintWriter stderr;

    private Tags tags;

    private YamlReader yamlReader;

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
        this.helpers = callbacks.getHelpers();

        this.stdout = new PrintWriter(callbacks.getStdout(), true);
        this.stderr = new PrintWriter(callbacks.getStderr(), true);

        // 标签界面
        this.tags = new Tags(callbacks, NAME);

        // 配置文件
        this.yamlReader = YamlReader.getInstance(callbacks);

        callbacks.setExtensionName(NAME);
        callbacks.registerScannerCheck(this);

        // 基本信息输出
        // 作者拿来臭美用的 ╰(*°▽°*)╯
        this.stdout.println(basicInformationOutput());
    }

    /**
     * 基本信息输出
     */
    private static String basicInformationOutput() {
        String str1 = "===================================\n";
        String str2 = String.format("%s 加载成功\n", NAME);
        String str3 = String.format("版本: %s\n", VERSION);
        String str4 = String.format("Modified by: %s\n", COMMIT);
        String str5 = "===================================\n";
        String detail = str1 + str2 + str3 + str4 + str5;
        return detail;
    }

    @Override
    public List<IScanIssue> doPassiveScan(IHttpRequestResponse baseRequestResponse) {
        List<IScanIssue> issues = new ArrayList<>();

        // 基础url解析
        CustomBurpUrl baseBurpUrl = new CustomBurpUrl(this.callbacks, baseRequestResponse);

        // 基础请求分析
        BurpAnalyzedRequest baseAnalyzedRequest = new BurpAnalyzedRequest(this.callbacks, this.tags, baseRequestResponse);

        // 消息等级-用于插件扫描队列界面的显示
        String messageLevel = this.yamlReader.getString("messageLevel");

        // 判断是否开启插件
        if (!this.tags.getBaseSettingTagClass().isStart()) {
            return null;
        }

        // 判断是否有允许扫描的类型
        if (this.tags.getBaseSettingTagClass().getScanTypeList().size() == 0) {
            return null;
        }

        // 判断当前请求后缀,是否为url黑名单后缀
        if (this.isUrlBlackListSuffix(baseBurpUrl)) {
            return null;
        }

        // 判断当前请求是否有符合扫描条件的参数
        if (!baseAnalyzedRequest.isSiteEligibleParameters()) {
            if (messageLevel.equals("ALL")) {
                this.tags.getScanQueueTagClass().add(
                        "",
                        this.helpers.analyzeRequest(baseRequestResponse).getMethod(),
                        baseBurpUrl.getHttpRequestUrl().toString(),
                        this.helpers.analyzeResponse(baseRequestResponse.getResponse()).getStatusCode() + "",
                        "request parameter no eligible",
                        baseRequestResponse
                );
            }
            return null;
        }

        // 判断当前站点问题数量是否超出了
        Integer issueNumber = this.yamlReader.getInteger("scan.issueNumber");
        if (issueNumber != 0) {
            Integer siteIssueNumber = this.getSiteIssueNumber(baseBurpUrl.getRequestDomainName());
            if (siteIssueNumber >= issueNumber) {
                if (messageLevel.equals("ALL") || messageLevel.equals("INFO")) {
                    this.tags.getScanQueueTagClass().add(
                            "",
                            this.helpers.analyzeRequest(baseRequestResponse).getMethod(),
                            baseBurpUrl.getHttpRequestUrl().toString(),
                            this.helpers.analyzeResponse(baseRequestResponse.getResponse()).getStatusCode() + "",
                            "the number of website problems has exceeded",
                            baseRequestResponse
                    );
                }
                return null;
            }
        }

        // 判断当前站点是否超出扫描数量了
        Integer siteScanNumber = this.yamlReader.getInteger("scan.siteScanNumber");
        if (siteScanNumber != 0) {
            Integer siteJsonNumber = this.getSiteNumber(baseBurpUrl.getRequestDomainName());
            if (siteJsonNumber >= siteScanNumber) {
                if (messageLevel.equals("ALL") || messageLevel.equals("INFO")) {
                    this.tags.getScanQueueTagClass().add(
                            "",
                            this.helpers.analyzeRequest(baseRequestResponse).getMethod(),
                            baseBurpUrl.getHttpRequestUrl().toString(),
                            this.helpers.analyzeResponse(baseRequestResponse.getResponse()).getStatusCode() + "",
                            "the number of website scans exceeded",
                            baseRequestResponse
                    );
                }
                return null;
            }
        }

        // 添加任务到面板中等待检测
        int tagId = this.tags.getScanQueueTagClass().add(
                "",
                this.helpers.analyzeRequest(baseRequestResponse).getMethod(),
                baseBurpUrl.getHttpRequestUrl().toString(),
                this.helpers.analyzeResponse(baseRequestResponse.getResponse()).getStatusCode() + "",
                "waiting for test results",
                baseRequestResponse
        );

        try {
            // 远程cmd扩展
            IScanIssue remoteCmdIssuesDetail = this.remoteCmdExtension(tagId, baseAnalyzedRequest);
            if (remoteCmdIssuesDetail != null) {
                issues.add(remoteCmdIssuesDetail);
                return issues;
            }

            // 未检测出来问题, 更新任务状态至任务栏面板
            this.tags.getScanQueueTagClass().save(
                    tagId,
                    "ALL",
                    this.helpers.analyzeRequest(baseRequestResponse).getMethod(),
                    baseBurpUrl.getHttpRequestUrl().toString(),
                    this.helpers.analyzeResponse(baseRequestResponse.getResponse()).getStatusCode() + "",
                    "[-] not found log4j2 command execution",
                    baseRequestResponse
            );
        } catch (TaskTimeoutException e) {
            this.stdout.println("========插件错误-超时错误============");
            this.stdout.println(String.format("url: %s", baseBurpUrl.getHttpRequestUrl().toString()));
            this.stdout.println("请使用该url重新访问,若是还多次出现此错误,则很有可能waf拦截");
            this.stdout.println("错误详情请查看Extender里面对应插件的Errors标签页");
            this.stdout.println("========================================");
            this.stdout.println(" ");

            this.tags.getScanQueueTagClass().save(
                    tagId,
                    "",
                    this.helpers.analyzeRequest(baseRequestResponse).getMethod(),
                    baseBurpUrl.getHttpRequestUrl().toString(),
                    this.helpers.analyzeResponse(baseRequestResponse.getResponse()).getStatusCode() + "",
                    "[x] scan task timed out",
                    baseRequestResponse
            );

            e.printStackTrace(this.stderr);
        } catch (Exception e) {
            this.stdout.println("========插件错误-未知错误============");
            this.stdout.println(String.format("url: %s", baseBurpUrl.getHttpRequestUrl().toString()));
            this.stdout.println("请使用该url重新访问,若是还多次出现此错误,则很有可能waf拦截");
            this.stdout.println("错误详情请查看Extender里面对应插件的Errors标签页");
            this.stdout.println("========================================");
            this.stdout.println(" ");

            this.tags.getScanQueueTagClass().save(
                    tagId,
                    "",
                    this.helpers.analyzeRequest(baseRequestResponse).getMethod(),
                    baseBurpUrl.getHttpRequestUrl().toString(),
                    this.helpers.analyzeResponse(baseRequestResponse.getResponse()).getStatusCode() + "",
                    "[x] unknown error",
                    baseRequestResponse
            );

            e.printStackTrace(this.stderr);
        } finally {
            this.stdout.println("================扫描完毕================");
            this.stdout.println(String.format("url: %s", baseBurpUrl.getHttpRequestUrl().toString()));
            this.stdout.println("========================================");
            this.stdout.println(" ");

            return issues;
        }
    }

    @Override
    public List<IScanIssue> doActiveScan(IHttpRequestResponse baseRequestResponse, IScannerInsertionPoint insertionPoint) {
        return null;
    }

    @Override
    public int consolidateDuplicateIssues(IScanIssue existingIssue, IScanIssue newIssue) {
        return 0;
    }

    /**
     * 远程cmd扩展
     *
     * @param tagId
     * @param analyzedRequest
     * @return IScanIssue issues
     * @throws ClassNotFoundException
     * @throws NoSuchMethodException
     * @throws InvocationTargetException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    private IScanIssue remoteCmdExtension(int tagId, BurpAnalyzedRequest analyzedRequest) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        String provider = this.yamlReader.getString("application.remoteCmdExtension.config.provider");

        if (!this.tags.getBaseSettingTagClass().isStartRemoteCmdExtension()) {
            return null;
        }

        DnsLog dnsLog = new DnsLog(this.callbacks, this.yamlReader.getString("dnsLogModule.provider"));
        RemoteCmd remoteCmd = new RemoteCmd(this.callbacks, analyzedRequest, dnsLog, this.yamlReader, provider);
        if (!remoteCmd.run().isIssue()) {
            return null;
        }

        IHttpRequestResponse httpRequestResponse = remoteCmd.run().getHttpRequestResponse();

        this.tags.getScanQueueTagClass().save(
                tagId,
                remoteCmd.run().getExtensionName(),
                this.helpers.analyzeRequest(httpRequestResponse).getMethod(),
                new CustomBurpUrl(this.callbacks, httpRequestResponse).getHttpRequestUrl().toString(),
                this.helpers.analyzeResponse(httpRequestResponse.getResponse()).getStatusCode() + "",
                "[+] found log4j2 command execution",
                remoteCmd.run().getHttpRequestResponse()
        );

        remoteCmd.run().consoleExport();
        return remoteCmd.run().export();
    }

    /**
     * 判断是否url黑名单后缀
     * 大小写不区分
     * 是 = true, 否 = false
     *
     * @param burpUrl
     * @return
     */
    private boolean isUrlBlackListSuffix(CustomBurpUrl burpUrl) {
        if (!this.yamlReader.getBoolean("urlBlackListSuffix.config.isStart")) {
            return false;
        }

        String noParameterUrl = burpUrl.getHttpRequestUrl().toString().split("\\?")[0];
        String urlSuffix = noParameterUrl.substring(noParameterUrl.lastIndexOf(".") + 1);

        List<String> suffixList = this.yamlReader.getStringList("urlBlackListSuffix.suffixList");
        if (suffixList == null || suffixList.size() == 0) {
            return false;
        }

        for (String s : suffixList) {
            if (s.toLowerCase().equals(urlSuffix.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 网站问题数量
     *
     * @param domainName
     * @return
     */
    private Integer getSiteIssueNumber(String domainName) {
        Integer number = 0;

        String issueName = this.yamlReader.getString("application.remoteCmdExtension.config.issueName");
        for (IScanIssue Issue : this.callbacks.getScanIssues(domainName)) {
            if (Issue.getIssueName().equals(issueName)) {
                number++;
            }
        }

        return number;
    }

    /**
     * 站点出现数量
     *
     * @param domainName
     * @return
     */
    private Integer getSiteNumber(String domainName) {
        Integer number = 0;
        for (IHttpRequestResponse requestResponse : this.callbacks.getSiteMap(domainName)) {
            number++;
        }
        return number;
    }
}
