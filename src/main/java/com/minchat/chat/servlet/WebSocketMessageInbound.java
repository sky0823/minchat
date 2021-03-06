package com.minchat.chat.servlet;

import com.alibaba.fastjson.JSON;
import com.minchat.chat.model.CtCustomerGuest;
import com.minchat.chat.model.CtOnlineCustomer;
import com.minchat.chat.service.ChatService;
import com.minchat.core.util.AppUtil;
import com.minchat.core.util.UniqueIdUtil;
import com.minchat.sys.model.SysUser;
import com.minchat.sys.service.SysUserService;
import org.apache.catalina.websocket.MessageInbound;
import org.apache.catalina.websocket.WsOutbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public class WebSocketMessageInbound extends MessageInbound {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageInbound.class);

    private ChatService chatService = (ChatService) AppUtil.getBean("chatServiceImpl");
    private SysUserService sysUserService = (SysUserService) AppUtil.getBean("sysUserServiceImpl");

    private final static AtomicInteger guestIds = new AtomicInteger(1);

    //当前连接的用户id
    private final String cuserId;

    //当前连接的用户id
    private final String guserId;

    //当前连接的用户guestName
    private String guestName;

    //当前连接的sessionId
    private final String sessionId;

    //当前连接的inboundId
    private final String inboundId;

    public WebSocketMessageInbound(String cuserId, String guserId, String sessionId) {
        this.cuserId = cuserId;
        this.guserId = guserId;
        this.sessionId = sessionId;
        this.inboundId = UniqueIdUtil.getGuidNoDash();
        this.guestName = "";
    }

    public String getCuserId() {
        return cuserId;
    }

    public String getGuserId() {
        return guserId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getInboundId() {
        return inboundId;
    }

    //建立连接的触发的事件
    @Override
    protected void onOpen(WsOutbound outbound) {
        //向连接池添加当前的连接对象
        WebSocketMessageInboundPool.addMessageInbound(this);
        //客服人员
        if (WebSocketMessageInboundPool.isCustomer(this.inboundId)) {
            chatService.addOnlineCustomer(this.getInboundId(), this.cuserId);
            SysUser sysUser = sysUserService.getUserById(Long.valueOf(this.cuserId));
            //向当前连接发送当前在线用户的列表
            Map<String, Object> map = new HashMap<String, Object>();
            map.put("type", "customer_join");
            map.put("myinboundId", this.inboundId);
            map.put("createtime", WebSocketMessageInboundPool.getCurrentTime());
            if (sysUser != null) {
                map.put("sysUser", sysUser);
            }
            WebSocketMessageInboundPool.sendMessageToInboundId(this.inboundId, JSON.toJSONString(map));
        } else {
            //随机指定一名在线客服人员接通连线
            CtOnlineCustomer randCustomer = chatService.getCustomerRandOne();
            if (randCustomer == null) {
                Map<String, Object> no_customermap = new HashMap<String, Object>();
                no_customermap.put("type", "no_customer");
                no_customermap.put("msg", AppUtil.getProperties("no_customer_msg"));
                no_customermap.put("nickname", AppUtil.getProperties("no_customer_nickname"));
                no_customermap.put("picture", AppUtil.getProperties("no_customer_picture"));
                WebSocketMessageInboundPool.sendMessageToInboundId(this.inboundId, JSON.toJSONString(no_customermap));
                return;
            }
            Map<String, Object> map = new HashMap<String, Object>();
            map.put("type", "guest_join");
            map.put("createtime", WebSocketMessageInboundPool.getCurrentTime());
            map.put("inboundId", this.inboundId);
            //系统普通用户
            if (WebSocketMessageInboundPool.isGuestUser(this.inboundId)) {
                chatService.addOnlineGuestUser(this.inboundId, this.guserId);
                SysUser sysUser = sysUserService.getUserById(Long.valueOf(this.guserId));
                if (sysUser != null) {
                    map.put("nickname", sysUser.getNickname());
                    map.put("picture", sysUser.getPicture());
                }
            } else {//其他无账号访客
                String nickname = "访客" + guestIds.getAndIncrement();
                this.guestName = nickname;
                chatService.addOnlineGuest(this.getInboundId(), nickname);
                map.put("nickname", nickname);
                map.put("picture", AppUtil.getProperties("default_guest_avatar"));
            }
            //向客服人员发送连接信息
            chatService.addCustomerGuest(randCustomer.getInboundId(), this.inboundId);
            WebSocketMessageInboundPool.sendMessageToInboundId(randCustomer.getInboundId(), JSON.toJSONString(map));

            SysUser customer = sysUserService.getUserById(Long.valueOf(randCustomer.getUserId()));
            if (customer != null) {
                map.put("cnickname", customer.getNickname());
                map.put("cpicture", customer.getPicture());
            }
            map.put("msg", AppUtil.getProperties("welcome_msg"));

            //向访客发送欢迎语
            WebSocketMessageInboundPool.sendMessageToInboundId(this.inboundId, JSON.toJSONString(map));
        }
    }

    @Override
    protected void onClose(int status) {
        CtCustomerGuest ctCustomerGuest = chatService.selectCusGusBygInboundId(this.inboundId);
        if (WebSocketMessageInboundPool.isCustomer(this.inboundId)) {
            chatService.delCustomerByInboundId(this.inboundId);
            //向在线用户发送当前用户退出的消息
            if (ctCustomerGuest == null) {
                return;
            }
            chatService.delCusGusByCinboundId(this.inboundId);
            Map<String, String> map = new HashMap<String, String>();
            map.put("type", "guest_leave");
            map.put("msg", "客服已离线！");
            WebSocketMessageInboundPool.sendMessageToInboundId(ctCustomerGuest.getgInboundId(), JSON.toJSONString(map));
        } else {
            //chatService.delCustomerByInboundId(this.inboundId);
            if (ctCustomerGuest == null) {
                return;
            }
            chatService.delCusGusByGinboundId(this.inboundId);
            Map<String, String> map = new HashMap<String, String>();
            map.put("type", "guest_leave");
            map.put("ginboundId", this.inboundId);
            //向在线用户发送当前用户退出的消息
            WebSocketMessageInboundPool.sendMessageToInboundId(ctCustomerGuest.getcInboundId(), JSON.toJSONString(map));
        }
        // 触发关闭事件，在连接池中移除连接
        WebSocketMessageInboundPool.removeMessageInbound(this);
    }

    @Override
    protected void onBinaryMessage(ByteBuffer message) throws IOException {
        throw new UnsupportedOperationException("Binary message not supported.");
    }

    //客户端发送消息到服务器时触发事件
    @Override
    protected void onTextMessage(CharBuffer message) throws IOException {
//		Map netStatusMap = new HashMap();
//		netStatusMap.put("netStatus", "1");
//		WebSocketMessageInboundPool.sendMessageToInboundId(this.inboundId,JSON.toJSONString(netStatusMap));
//		
        Map map = JSON.parseObject(message.toString());
        if (map.containsKey("type")) {
            String type = String.valueOf(map.get("type"));
            //保存聊天记录 sendType 1:客服给有账号访客发送; 2:客服给无账号访客发送; 3:有账号访客给客服发送; 4：无账号访客给客服发送;
            String guestName = "";
            int sendType = 0;

            if ("heart_connect".equals(type)) {
                return;
            }

            //向访客人员发送消息
            if ("user_send".equals(type)) {
                String ginboundId = String.valueOf(map.get("ginboundId"));
                Map toGuestMap = new HashMap();
                toGuestMap.put("createtime", WebSocketMessageInboundPool.getCurrentTime());
                toGuestMap.put("type", "user_send");
                toGuestMap.put("msg", String.valueOf(map.get("msg")));
                WebSocketMessageInboundPool.sendMessageToInboundId(ginboundId, JSON.toJSONString(toGuestMap));

                //保存聊天记录
                if (WebSocketMessageInboundPool.isGuestUser(ginboundId)) {
                    guestName = WebSocketMessageInboundPool.getInboundGuserId(ginboundId);
                    sendType = 1;
                } else {//其他无账号访客
                    guestName = WebSocketMessageInboundPool.getInboundGuestName(ginboundId);
                    sendType = 2;
                }
                chatService.addCtUserMsg(sendType, this.cuserId, guestName, String.valueOf(map.get("msg")));
            } else { //("guest_send".equals(type)){
                CtCustomerGuest ctCustomerGuest = chatService.selectCusGusBygInboundId(this.inboundId);
                if (ctCustomerGuest == null) {
                    logger.info("断线了");
                    return;
                }
                //向客服人员发送消息
                Map<String, Object> msgMap = new HashMap<String, Object>();
                msgMap.put("type", "guest_send");
                msgMap.put("ginboundId", this.inboundId);
                msgMap.put("msg", String.valueOf(map.get("msg")));
                WebSocketMessageInboundPool.sendMessageToInboundId(ctCustomerGuest.getcInboundId(), JSON.toJSONString(msgMap));

                //保存聊天记录
                if (WebSocketMessageInboundPool.isGuestUser(this.inboundId)) {
                    guestName = WebSocketMessageInboundPool.getInboundGuserId(this.inboundId);
                    sendType = 3;
                } else {//其他无账号访客
                    guestName = WebSocketMessageInboundPool.getInboundGuestName(this.inboundId);
                    sendType = 4;
                }
                chatService.addCtUserMsg(sendType, guestName, WebSocketMessageInboundPool.getInboundCuserId(ctCustomerGuest.getcInboundId()), String.valueOf(map.get("msg")));
            }
        }

    }

    public String getGuestName() {
        return guestName;
    }
}
