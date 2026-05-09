package top.srcrs;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.domain.Cookie;
import top.srcrs.util.Encryption;
import top.srcrs.util.Request;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 程序运行开始的地方
 *
 * @author srcrs
 * @Time 2020-10-31
 */
public class Run {
    /**
     * 获取日志记录器对象
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Run.class);

    /**
     * 获取用户所有关注贴吧
     */
    String LIKE_URL = "https://tieba.baidu.com/mo/q/newmoindex";
    /**
     * 获取用户的tbs
     */
    String TBS_URL = "http://tieba.baidu.com/dc/common/tbs";
    /**
     * 贴吧签到接口
     */
    String SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign";

    /**
     * 存储用户所关注的贴吧
     */
    private List<String> follow = new ArrayList<>();
    /**
     * 签到成功的贴吧列表
     */
    private static List<String> success = new ArrayList<>();

    /**
     * 签到失败的贴吧列表
     */
    private static HashSet<String> failed = new HashSet<String>();

    /**
     * 失效的贴吧列表
     */
    private static List<String> invalid = new ArrayList<>();

    /**
     * 用户的tbs
     */
    private String tbs = "";
    /**
     * 用户所关注的贴吧数量
     */
    private static Integer followNum = 201;

    /**
     * 需要回复的帖子配置列表 (贴吧名 -> (帖子tid -> 回复内容列表))
     */
    private Map<String, Map<String, List<String>>> postConfig = new HashMap<>();

    public static void main(String[] args) {
        Cookie cookie = Cookie.getInstance();
        // 存入Cookie，以备使用
        if (args.length == 0) {
            LOGGER.warn("请在Secrets中填写BDUSS");
        }
        cookie.setBDUSS(args[0]);
        Run run = new Run();
        run.getTbs();
        run.getFollow();
        run.runSign();
        LOGGER.info("共 {} 个贴吧 - 成功: {} - 失败: {} - {} ", followNum, success.size(), followNum - success.size(), failed);
        LOGGER.info("失效 {} 个贴吧: {} ", invalid.size(), invalid);
        
        // 执行帖子回复
        run.initPostConfig();
        run.replyPosts();
        
        if (args.length == 2) {
            run.send(args[1]);
        }
    }

    /**
     * 进行登录，获得 tbs ，签到的时候需要用到这个参数
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getTbs() {
        try {
            JSONObject jsonObject = Request.get(TBS_URL);
            if ("1".equals(jsonObject.getString("is_login"))) {
                LOGGER.info("获取tbs成功");
                tbs = jsonObject.getString("tbs");
            } else {
                LOGGER.warn("获取tbs失败 -- " + jsonObject);
            }
        } catch (Exception e) {
            LOGGER.error("获取tbs部分出现错误 -- " + e);
        }
    }

    /**
     * 获取用户所关注的贴吧列表
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getFollow() {
        try {
            JSONObject jsonObject = Request.get(LIKE_URL);
            LOGGER.info("获取贴吧列表成功");
            JSONArray jsonArray = jsonObject.getJSONObject("data").getJSONArray("like_forum");
            followNum = jsonArray.size();
            // 获取用户所有关注的贴吧
            for (Object array : jsonArray) {
                String tiebaName = ((JSONObject) array).getString("forum_name");
                if ("0".equals(((JSONObject) array).getString("is_sign"))) {
                    // 将为签到的贴吧加入到 follow 中，待签到
                    follow.add(tiebaName.replace("+", "%2B"));
                    // 过滤失效的贴吧
                    if (Request.isTiebaNotExist(tiebaName)) {
                        follow.remove(tiebaName);
                        invalid.add(tiebaName);
                        failed.add(tiebaName);
                    }
                } else {
                    // 将已经成功签到的贴吧，加入到 success
                    success.add(tiebaName);
                }
            }
        } catch (Exception e) {
            LOGGER.error("获取贴吧列表部分出现错误 -- " + e);
        }
    }

    /**
     * 开始进行签到，每一轮性将所有未签到的贴吧进行签到，一共进行5轮，如果还未签到完就立即结束
     * 一般一次只会有少数的贴吧未能完成签到，为了减少接口访问次数，每一轮签到完等待1分钟，如果在过程中所有贴吧签到完则结束。
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void runSign() {
        // 当执行 5 轮所有贴吧还未签到成功就结束操作
        Integer flag = 5;
        try {
            while (success.size() < followNum && flag > 0) {
                LOGGER.info("-----第 {} 轮签到开始-----", 5 - flag + 1);
                LOGGER.info("还剩 {} 贴吧需要签到", followNum - success.size());
                Iterator<String> iterator = follow.iterator();
                while (iterator.hasNext()) {
                    String s = iterator.next();
                    String rotation = s.replace("%2B", "+");
                    String body = "kw=" + s + "&tbs=" + tbs + "&sign=" + Encryption.enCodeMd5("kw=" + rotation + "tbs=" + tbs + "tiebaclient!!!");
                    JSONObject post = new JSONObject();
                    post = Request.post(SIGN_URL, body);
                    int randomTime = new Random().nextInt(200) + 300;
                    LOGGER.info("等待 {} 毫秒", randomTime);
                    TimeUnit.MILLISECONDS.sleep(randomTime);
                    if ("0".equals(post.getString("error_code"))) {
                        iterator.remove();
                        success.add(rotation);
                        failed.remove(rotation);
                        LOGGER.info(rotation + ": " + "签到成功");
                    } else {
                        failed.add(rotation);
                        LOGGER.warn(rotation + ": " + "签到失败");
                    }
                }
                if (success.size() != followNum - invalid.size()) {
                    // 为防止短时间内多次请求接口，触发风控，设置每一轮签到完等待 5 分钟
                    Thread.sleep(1000 * 60 * 5);
                    /**
                     * 重新获取 tbs
                     * 尝试解决以前第 1 次签到失败，剩余 4 次循环都会失败的错误。
                     */
                    getTbs();
                }
                flag--;
            }
        } catch (Exception e) {
            LOGGER.error("签到部分出现错误 -- " + e);
        }
    }

    /**
     * 发送运行结果到微信，通过 server 酱
     *
     * @param sckey
     * @author srcrs
     * @Time 2020-10-31
     */
    /**   public void send(String sckey) {
       
        String text = "总: " + followNum + " - ";
        text += "成功: " + success.size() + " 失败: " + (followNum - success.size());
        String desp = "共 " + followNum + " 贴吧\n\n";
        desp += "成功: " + success.size() + " 失败: " + (followNum - success.size());
        String body = "text=" + text + "&desp=" + "TiebaSignIn运行结果\n\n" + desp;
        StringEntity entityBody = new StringEntity(body, "UTF-8");
        HttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("https://sc.ftqq.com/" + sckey + ".send");
        httpPost.addHeader("Content-Type", "application/x-www-form-urlencoded");
        httpPost.setEntity(entityBody);
        HttpResponse resp = null;
        String respContent = null;
        try {
            resp = client.execute(httpPost);
            HttpEntity entity = null;
            if (resp.getStatusLine().getStatusCode() < 400) {
                entity = resp.getEntity();
            } else {
                entity = resp.getEntity();
            }
            respContent = EntityUtils.toString(entity, "UTF-8");
            LOGGER.info("server酱推送正常");
        } catch (Exception e) {
            LOGGER.error("server酱发送失败 -- " + e);
        }
    } 
**/
      /**
     * 发送运行结果到微信，通过 PUSHPLUS
     *
     * @param sckey
     * @author srcrs
     * @Time 2020-10-31
     */
     public void send(String sckey) {
        /** 将要推送的数据 */
        String text = "总: " + followNum + " - ";
        text += "成功: " + success.size() + " 失败: " + (followNum - success.size());
        String desp = "共 " + followNum + " 贴吧\n\n";
        desp += "成功: " + success.size() + " 失败: " + (followNum - success.size());
        String body = "text=" + text + "&desp=" + "TiebaSignIn运行结果\n\n" + desp;

try {
            String token = sckey;
            String title = URLEncoder.encode("百度贴吧自动签到", "UTF-8");
            String content = URLEncoder.encode(desp, "UTF-8");
            String urlx = "https://www.pushplus.plus/send?title=" + title + "&content=" + content + "&token=" + token;
            URL url = new URL(urlx);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            StringBuilder response = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            System.out.println("Response: " + response.toString());
            connection.disconnect();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 帖子配置
     * @author srcrs
     * @Time 2026-05-09
     */
    public void initPostConfig() {
         postConfig.put("崩坏3rd", new HashMap<String, List<String>>() {{
             put("10701596086", Collections.singletonList(
                     "日常打卡，祝大家好运！"
             ));
         }});
         postConfig.put("火影忍者", new HashMap<String, List<String>>() {{
             put("10701607087", Collections.singletonList(
                     "日常打卡，祝大家好运！"
             ));
         }});
        
        LOGGER.info("帖子回复配置初始化完成，共配置 {} 个帖子", postConfig.size());
    }

    /**
     * 执行帖子回复操作
     * @author srcrs
     * @Time 2026-05-09
     */
    public void replyPosts() {
        if (postConfig.isEmpty()) {
            LOGGER.info("未配置任何帖子回复任务");
            return;
        }

        LOGGER.info("-----开始执行帖子回复-----");
        int successCount = 0;
        int failCount = 0;

        for (Map.Entry<String, Map<String, List<String>>> entry : postConfig.entrySet()) {
            String tiebaName = entry.getKey();
            Map<String, List<String>> posts = entry.getValue();

            for (Map.Entry<String, List<String>> postEntry : posts.entrySet()) {
                String tid = postEntry.getKey();
                List<String> contents = postEntry.getValue();

                LOGGER.info("正在处理贴吧: {}, 帖子tid: {}", tiebaName, tid);

                // 限制最多回复3条
                int maxReplies = Math.min(contents.size(), 3);
                
                for (int i = 0; i < maxReplies; i++) {
                    String content = contents.get(i);
                    boolean result = replyToPost(tiebaName, tid, content);
                    
                    if (result) {
                        successCount++;
                        LOGGER.info("回复成功 [{}]: {}", tid, content);
                    } else {
                        failCount++;
                        LOGGER.warn("回复失败 [{}]: {}", tid, content);
                    }

                    // 每条回复之间随机延迟 1-3 秒，避免触发风控
                    try {
                        int randomDelay = new Random().nextInt(2000) + 1000;
                        LOGGER.info("等待 {} 毫秒", randomDelay);
                        TimeUnit.MILLISECONDS.sleep(randomDelay);
                    } catch (InterruptedException e) {
                        LOGGER.error("延迟被中断: " + e);
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        LOGGER.info("-----帖子回复完成----- 成功: {}, 失败: {}", successCount, failCount);
    }

    /**
     * 向指定帖子回复内容
     * @author srcrs
     * @Time 2026-05-09
     */
    public boolean replyToPost(String tiebaName, String tid, String content) {
        try {
            String replyUrl = "http://c.tieba.baidu.com/c/c/post/add";
            
            String signStr = "content=" + content + "kw=" + tiebaName + "tbs=" + tbs + "tid=" + tid + "tiebaclient!!!";
            String sign = Encryption.enCodeMd5(signStr);
            
            StringBuilder params = new StringBuilder();
            params.append("kw=").append(URLEncoder.encode(tiebaName, "UTF-8"));
            params.append("&tid=").append(tid);
            params.append("&content=").append(URLEncoder.encode(content, "UTF-8"));
            params.append("&tbs=").append(tbs);
            params.append("&sign=").append(sign);
            JSONObject response = Request.post(replyUrl, params.toString());
            if (response != null && "0".equals(response.getString("error_code"))) {
                return true;
            } else {
                LOGGER.warn("回复失败，错误码: {}, 错误信息: {}", 
                    response != null ? response.getString("error_code") : "null",
                    response != null ? response.getString("error_msg") : "null");
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("回复帖子时发生异常: " + e);
            return false;
        }
    }
}
