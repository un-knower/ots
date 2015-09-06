package com.mk.ots.order.service;

import java.io.File;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.mk.framework.AppUtils;
import com.mk.framework.exception.MyErrorEnum;
import com.mk.framework.util.MyTokenUtils;
import com.mk.framework.util.ThreadUtil;
import com.mk.orm.plugin.bean.Bean;
import com.mk.ots.common.bean.Ethnic;
import com.mk.ots.common.enums.ButtonTypeEnum;
import com.mk.ots.common.enums.CardTypeEnum;
import com.mk.ots.common.enums.OrderTypeEnum;
import com.mk.ots.common.enums.OtaOrderStatusEnum;
import com.mk.ots.common.enums.PPayInfoTypeEnum;
import com.mk.ots.common.enums.PayStatusEnum;
import com.mk.ots.common.enums.PayTitleTypeEnum;
import com.mk.ots.common.enums.SexEnum;
import com.mk.ots.common.enums.TicketUselimitEnum;
import com.mk.ots.common.utils.Constant;
import com.mk.ots.common.utils.DateUtils;
import com.mk.ots.common.utils.SysConfig;
import com.mk.ots.hotel.model.THotel;
import com.mk.ots.hotel.service.HotelService;
import com.mk.ots.manager.SysConfigManager;
import com.mk.ots.order.bean.OtaCheckInUser;
import com.mk.ots.order.bean.OtaOrder;
import com.mk.ots.order.bean.OtaRoomOrder;
import com.mk.ots.order.bean.OtaRoomPrice;
import com.mk.ots.order.dao.CheckInUserDAO;
import com.mk.ots.pay.model.PPay;
import com.mk.ots.pay.model.PPayInfo;
import com.mk.ots.ticket.model.TicketInfo;
import com.mk.ots.ticket.service.ITicketService;
import com.mk.ots.ticket.service.impl.TicketService;

@Service
public class OrderUtil {

	@Autowired
	CheckInUserDAO checkInUserDAO;
	
	public static final int BORDERLINEHOUR = 6;

	public static Logger logger = LoggerFactory.getLogger(OrderUtil.class);

	/**
	 * 显示订单需要显示的按钮状态
	 * 
	 * @param order
	 * @return
	 */
	public static PayTitleTypeEnum getTitle(OtaOrder order) {
		PayTitleTypeEnum titleTypeEnum = null;
		if (order.getOrderStatus() == OtaOrderStatusEnum.wait.getId()) {
			HotelService hotelService = AppUtils.getBean(HotelService.class);
			THotel tHotel = hotelService.readonlyTHotel(order.getHotelId());
			if (tHotel.getStr("needwait") != null) {
				titleTypeEnum = PayTitleTypeEnum.needwait;
			} else {
				titleTypeEnum = PayTitleTypeEnum.doNotPayCanPay;
			}
		} else if (order.getOrderStatus() == OtaOrderStatusEnum.WaitPay.getId()) {
			if (order.getOrderType() == OrderTypeEnum.PT.getId()) {
				titleTypeEnum = PayTitleTypeEnum.arrivePay;
			} else if (order.getOrderType() == OrderTypeEnum.YF.getId()) {
				titleTypeEnum = PayTitleTypeEnum.doNotPayCanPay;
			} else {
				titleTypeEnum = PayTitleTypeEnum.doNotPayCanPay;
			}
		} else if (order.getOrderStatus() >= OtaOrderStatusEnum.Confirm.getId() && order.getOrderStatus() <= OtaOrderStatusEnum.CheckInOnline.getId()) {
			if (order.getOrderType() == OrderTypeEnum.PT.getId()) {
				titleTypeEnum = PayTitleTypeEnum.doNotPaynotPay;
			} else {
				if (order.getPayStatus() == PayStatusEnum.waitPay.getId()) {
					titleTypeEnum = PayTitleTypeEnum.arrivePay;
				} else if (order.getPayStatus() == PayStatusEnum.alreadyPay.getId()) {
					titleTypeEnum = PayTitleTypeEnum.pay;
				}
			}
		} else if (order.getOrderStatus() >= OtaOrderStatusEnum.CheckIn.getId() && order.getOrderStatus() < OtaOrderStatusEnum.CheckOut.getId()) {
			titleTypeEnum = PayTitleTypeEnum.CheckIn;
		} else if (order.getOrderStatus() == OtaOrderStatusEnum.CheckOut.getId()) {
			titleTypeEnum = PayTitleTypeEnum.OK;
		} else if (order.getOrderStatus() >= OtaOrderStatusEnum.CancelByU_WaitRefund.getId()
				&& order.getOrderStatus() <= OtaOrderStatusEnum.CancelByU_NoRefund.getId()) {
			if (order.getOrderStatus() == OtaOrderStatusEnum.CancelByU_WaitRefund.getId()) {
				titleTypeEnum = PayTitleTypeEnum.cancel_waitPay;
			} else {
				titleTypeEnum = PayTitleTypeEnum.cancel;
			}
		} else if (order.getOrderStatus() == OtaOrderStatusEnum.NotIn.getId()) {
			if (order.getPayStatus() == PayStatusEnum.waitPay.getId()) {
				titleTypeEnum = PayTitleTypeEnum.arrivePay;
			} else if (order.getPayStatus() == PayStatusEnum.alreadyPay.getId()) {
				titleTypeEnum = PayTitleTypeEnum.pay;
			} else {
				titleTypeEnum = PayTitleTypeEnum.NotIn;
			}
		}
		return titleTypeEnum;
	}

	/**
	 * 订单转换为 jsonobj
	 * 
	 * @param jsonObj
	 * @param returnOrder
	 * @param showRoom
	 * @param showInUser
	 */
	public void getOrderToJson(JSONObject jsonObj, PPay pay, OtaOrder returnOrder, boolean showRoom, boolean showInUser) {
		THotel hotel = getThotelThreadLocal(returnOrder.getHotelId());
		String lastrefundtime = SysConfig.getInstance().getSysValueByKey("lastrefundtime");
		String refundrule = SysConfig.getInstance().getSysValueByKey("refundrule");
		JSONObject refundruleJson = JSON.parseObject(refundrule);
		Bean city = hotel.getTCityByDisId();
		Bean district = hotel.getTDistrictByDisId();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
		// DecimalFormat format=new DecimalFormat("#.00");
		// format.setRoundingMode(RoundingMode.UP);
		jsonObj.put("orderid", returnOrder.getId());
		jsonObj.put("hotelid", returnOrder.getHotelId());
		jsonObj.put("hotelname", returnOrder.getHotelName());
		jsonObj.put("hotelphone", hotel.get("hotelphone", ""));// 联系电话
		jsonObj.put("hoteladdress", returnOrder.get("hoteladdress", ""));
		JSONArray hotelPics = JSONArray.parseArray((String) returnOrder.get("hotelpic", "[]"));
		jsonObj.put("hotelpic", hotelPics);
		jsonObj.put("hoteldis", district.get("disname"));// 酒店所属区县
		jsonObj.put("hotelcity", city.get("cityname"));// 酒店所属城市
		jsonObj.put("longitude", hotel.get("longitude"));// 酒店坐标(经度)
		jsonObj.put("latitude", hotel.get("latitude"));// 酒店坐标(纬度)
		jsonObj.put("retentiontime", returnOrder.get("retentiontime", "180000"));
		jsonObj.put("defaultleavetime", returnOrder.get("defaultleavetime", "120000"));
		jsonObj.put("ordermethod", returnOrder.getOrderMethod());
		jsonObj.put("ordertype", returnOrder.getOrderType());
		jsonObj.put("pricetype", returnOrder.getPriceType());
		jsonObj.put("begintime", sdf.format(returnOrder.get("begintime")));
		jsonObj.put("endtime", sdf.format(returnOrder.get("endtime")));
		jsonObj.put("createtime", sdf.format(returnOrder.get("createtime")));
		// 订单失效时间（创建订单时，默认是预付，订单失效时间为订单创建后15分钟）
		jsonObj.put("timeouttime", DateUtils.formatDateTime(DateUtils.addMinutes(returnOrder.getDate("createtime"), 15)));
		jsonObj.put("promotion", returnOrder.getPromotion());
		jsonObj.put("coupon", returnOrder.getCoupon());
		jsonObj.put("isscore", returnOrder.get("isscore") == null ? "F" : returnOrder.get("isscore"));// 是否已评价(T/F)
		ITicketService ticketService = AppUtils.getBean(TicketService.class);

		List<TicketInfo> tickes = null;
		if (returnOrder.getStr("act") != null && returnOrder.getStr("act").equals("create")) {
			// 订单修改（查询券）
			tickes = ticketService.getBindOrderAndAvailableTicketInfos(returnOrder, MyTokenUtils.getMidByToken(""));
		} else if (returnOrder.getOrderStatus() < OtaOrderStatusEnum.Confirm.getId()) {
			// 订单修改（查询券）
			tickes = ticketService.getBindOrderAndAvailableTicketInfos(returnOrder, MyTokenUtils.getMidByToken(""));
		} else if (returnOrder.getOrderStatus() >= OtaOrderStatusEnum.Confirm.getId()) {
			// 查询订单（查询券）
			tickes = ticketService.getOrderAlreadyBindTickets(returnOrder);
		}
		int checkcnt = 0;
		if(CollectionUtils.isNotEmpty(tickes)){
			//checkcnt:,//可使用优惠券张数 后续如果优惠券分页查询，此处要改为从数据库查询总数
			for(TicketInfo tmpti: tickes){
				if(tmpti.getCheck()){
					checkcnt++;
				}
			}
		}
		jsonObj.put("checkcnt", checkcnt);
		jsonObj.put("tickets", getJsonByTicketInfo(tickes));

		jsonObj.put("totalprice", returnOrder.getTotalPrice().setScale(0, BigDecimal.ROUND_UP));// 向上取整数
		jsonObj.put("price", returnOrder.getPrice().setScale(0, BigDecimal.ROUND_UP));// 向上取整数
		jsonObj.put("breakfastnum", returnOrder.getBreakfastNum());
		jsonObj.put("contacts", returnOrder.get("Contacts", ""));
		jsonObj.put("contactsphone", returnOrder.get("ContactsPhone", ""));
		jsonObj.put("contactsemail", returnOrder.get("ContactsEmail", ""));
		jsonObj.put("contactsweixin", returnOrder.get("ContactsWeiXin", ""));
		jsonObj.put("note", returnOrder.get("Note", ""));
		jsonObj.put("orderstatus", returnOrder.getOrderStatus());
		// jsonObj.put("canshow", returnOrder.getCanshow().equals("T"));// true
		// or false
		// 最后可退款时间
		jsonObj.put("lastrefundtime",
				DateUtils.getStringFromDate(DateUtils.addDays(returnOrder.getDate("begintime"), -1), DateUtils.FORMATSHORTDATETIME) + lastrefundtime);
		if (sdf.format(returnOrder.get("createtime")).startsWith(
				DateUtils.getStringFromDate(returnOrder.getDate("begintime"), DateUtils.FORMATSHORTDATETIME))) {
			String refundruleStr = refundruleJson.getString("ONE_DAY");
			jsonObj.put("refundrule", refundruleStr);
		} else {
			String refundruleStr = refundruleJson.getString("MORE_DAY");
			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < lastrefundtime.length(); i++) {
				sb.append(lastrefundtime.substring(i, i + 2)).append(":");
				i++;
			}
			sb.setLength(sb.length() - 1);
			jsonObj.put("refundrule", MessageFormat.format(refundruleStr, new String[] { sb.toString() }));
		}

		// 第三方支付
		if (pay != null && pay.getpOrderLog() != null && pay.getpOrderLog().getBigDecimal("usercost") != null) {
			BigDecimal needpay = pay.getpOrderLog().getBigDecimal("usercost").subtract(pay.getpOrderLog().getBigDecimal("realcost"));
			if (needpay.compareTo(BigDecimal.ZERO) > 0) {
				List<PPayInfo> list = pay.getPayInfos();
				boolean find = false;
				if (list != null) {
					for (PPayInfo pPayInfo : list) {
						if (pPayInfo.getType() == PPayInfoTypeEnum.Z2P) {// TODO
																			// 是否有第三方支付
							find = true;
							break;
						}
					}
				}
				if (find && returnOrder.getOrderType() == OrderTypeEnum.YF.getId()) {// 预付
					jsonObj.put("pay", true ? "T" : "F");
					String online = null;
					online = SysConfigManager.getInstance().readOne(Constant.mikewebtype, Constant.online);
					if (StringUtils.isNotBlank(online) && !Boolean.parseBoolean(online)) {
						jsonObj.put("payid", "TXT" + pay.getId().toString());
					} else {
						jsonObj.put("payid", pay.getId().toString());
					}
					jsonObj.put("onlinepay", needpay.setScale(0, BigDecimal.ROUND_UP));
				} else if (returnOrder.getOrderType() == OrderTypeEnum.PT.getId()) {// 到付
					jsonObj.put("offlinepay", needpay.setScale(0, BigDecimal.ROUND_UP));
					jsonObj.put("pay", false ? "T" : "F");
				} else {
					jsonObj.put("pay", false ? "T" : "F");
				}
			} else {
				jsonObj.put("pay", false ? "T" : "F");
			}
		} else {
			BigDecimal promotionprice = new BigDecimal(0);
			String uselimit = "";
			for (TicketInfo ticketInfo : tickes) {
				// 查找选中的优惠券
				if (ticketInfo.isSelect()) {
					if (TicketUselimitEnum.ALL.getType().equals(ticketInfo.getUselimit())||TicketUselimitEnum.YF.getType().equals(ticketInfo.getUselimit())) {// 订单为到付
						logger.info("OrderUtil::循环TicketInfo::" + ticketInfo + "::" + ticketInfo.getSubprice());
						promotionprice = promotionprice.add(ticketInfo.getSubprice());
						//重写优惠券价格，供实际支付使用
						promotionprice = rewritePromotionprice(returnOrder.getTotalPrice(), promotionprice,ticketInfo.getActivityid());
						
					} else if(TicketUselimitEnum.PT.getType().equals(ticketInfo.getUselimit())){
						logger.info("OrderUtil::循环TicketInfo::" + ticketInfo + "::" + ticketInfo.getOfflineprice());
						promotionprice = promotionprice.add(ticketInfo.getOfflineprice());
					}
					if(StringUtils.isEmpty(uselimit)){
						uselimit = ticketInfo.getUselimit();
					}
				}
			}
			
			
			if (returnOrder.getTotalPrice().compareTo(promotionprice) > 0) {
				promotionprice = returnOrder.getTotalPrice().subtract(promotionprice);
			} else {
				promotionprice = new BigDecimal(0);
			}
			LoggerFactory.getLogger(OrderUtil.class).info("OrderUtil::议价价钱::" + promotionprice.longValue());
			
			jsonObj.put("uselimit", uselimit);
			if(returnOrder.getSpreadUser() != null){//切客时
				jsonObj.put("onlinepay", promotionprice);
				jsonObj.put("offlinepay", promotionprice);
				
			}else{
				
				if (TicketUselimitEnum.ALL.getType().equals(uselimit)){
					jsonObj.put("onlinepay", promotionprice);
					jsonObj.put("offlinepay", promotionprice);
				}else if(TicketUselimitEnum.YF.getType().equals(uselimit)) {// 订单为到付
					jsonObj.put("onlinepay", promotionprice);
					jsonObj.put("offlinepay", returnOrder.getTotalPrice());//如果前端用了线下支付的，就显示原价
				} else if(TicketUselimitEnum.PT.getType().equals(uselimit)){
					jsonObj.put("offlinepay", promotionprice);
					jsonObj.put("onlinepay", returnOrder.getTotalPrice());//如果前端用了在线支付的，就显示原价
				}
			}
			
		}
		jsonObj.put("orderretentiontime",
				DateUtils.getStringFromDate(DateUtils.addMinutes(returnOrder.getDate("createtime"), 15), DateUtils.FORMATDATETIME));
		jsonObj.put("receipt", returnOrder.getReceipt());
		if (returnOrder.getSpreadUser() != null) { // 切客
			jsonObj.put("spreaduser", returnOrder.getSpreadUser());
		} else {
			jsonObj.put("spreaduser", "");
		}
		// jsonObj.put("daynumber", returnOrder.getDaynumber());//TODO 目前只有一个客单
		// 只选一个时间
		JSONArray roomOrder = new JSONArray();
		List<OtaRoomPrice> otaRoomPrices = returnOrder.get("otaRoomPrices");
		for (OtaRoomOrder room : returnOrder.getRoomOrderList()) {
			JSONObject jsonRoom = new JSONObject();
			jsonRoom.put("orderroomid", room.get("id"));
			jsonRoom.put("hotelid", room.get("Hotelid"));
			jsonRoom.put("hotelname", room.get("Hotelname"));
			jsonRoom.put("roomtypeid", room.get("RoomTypeId"));
			jsonRoom.put("roomtypename", room.get("RoomTypeName"));
			jsonRoom.put("roomid", room.get("RoomId"));
			jsonRoom.put("roomno", room.get("RoomNo"));
			jsonRoom.put("pic", room.get("roomTypePic"));
			jsonRoom.put("ordermethod", room.get("orderMethod"));
			jsonRoom.put("ordertype", room.get("orderType"));
			jsonRoom.put("pricetype", room.get("priceType"));
			jsonRoom.put("begintime", sdf.format(room.getDate("beginTime")));
			jsonRoom.put("endtime", sdf.format(room.get("endTime")));
			jsonRoom.put("orderday", room.get("orderDay") == null ? returnOrder.getDaynumber() : room.getLong("orderDay"));
			jsonRoom.put("createtime", sdf.format(returnOrder.get("createTime")));
			jsonRoom.put("promotion", room.get("promotion"));
			jsonRoom.put("coupon", room.get("coupon"));
			jsonRoom.put("totalprice", room.getBigDecimal("totalPrice") != null ? room.getBigDecimal("totalPrice").setScale(0, BigDecimal.ROUND_UP) : 0);
			{
				JSONArray priceArray = new JSONArray();
				if (otaRoomPrices != null) {
					
					Collections.sort(otaRoomPrices, new Comparator<OtaRoomPrice>() {
						@Override
						public int compare(OtaRoomPrice arg0, OtaRoomPrice arg1) {
							return arg0.getStr("ActionDate").compareTo(arg1.getStr("ActionDate"));
						}
					});
					int c = 0;
					for (OtaRoomPrice roomPrice : otaRoomPrices) {
						if (roomPrice.getLong("otaroomorderid").longValue() == room.getLong("id")) {
							JSONObject priceObj = new JSONObject();
							priceObj.put("actiondate", roomPrice.getStr("ActionDate"));
							priceObj.put("price", roomPrice.getBigDecimal("Price").setScale(0, BigDecimal.ROUND_UP));
							priceArray.add(priceObj);
							if(++c == returnOrder.getDaynumber()){
								break;
							}
						}
					}
				}
				jsonRoom.put("payprice", priceArray);
			}
			// jsonRoom.put("price", room.getBigDecimal("price").setScale(0,
			// BigDecimal.ROUND_UP));//TODO 向上取整 整数
			jsonRoom.put("breakfastnum", room.get("breakfastNum"));
			jsonRoom.put("contacts", room.get("contacts", ""));
			jsonRoom.put("contactsphone", room.get("contactsPhone", ""));
			jsonRoom.put("contactsemail", room.get("contactsEmail", ""));
			jsonRoom.put("contactsweixin", room.get("contactsWeiXin", ""));
			jsonRoom.put("note", room.get("note", ""));
			jsonRoom.put("pay", pay == null ? "T" : "F"); // 是否需要支付
			jsonRoom.put("receipt", room.get("receipt"));// 是否需要发票
			jsonRoom.put("promotionno", room.get("promotionNo", ""));// 促销代码
			jsonRoom.put("reeceipttitle", room.get("reeceiptTitle", ""));// ’发票抬头’,续住时需要第三方支付金额

			// if(pay!=null){//TODO 客单暂时没有payid
			// jsonRoom.put("pay", true); // 是否支付
			// jsonRoom.put("needpay", WWW);// 续住时需要第三方支付金额
			// }
			// jsonRoom.put("needpay", 0);// 续住时需要第三方支付金额
			// 订单状态 设置按钮 设置显示
			PayTitleTypeEnum titleTypeEnum = OrderUtil.getTitle(returnOrder);
			if (titleTypeEnum != null) {
				JSONArray jbuttons = new JSONArray();
				for (ButtonTypeEnum buttonType : titleTypeEnum.getButtons()) {
					JSONObject jButton = new JSONObject();
					jButton.put("name", buttonType.getInfo());
					jButton.put("action", buttonType.getName());
					jbuttons.add(jButton);
				}
				jsonRoom.put("showtitle", titleTypeEnum.getName());

				jsonRoom.put("button", jbuttons);
			}
			
			// checkinuser:[{cpname:入住人姓名 cpsex:入住人性别 birthday:生日 cardtype:证件类型 cardid:证件号ethnic:民族 fromaddress:户籍地址 address:联系地址 }]
//			if(returnOrder.getOrderStatus() >= OtaOrderStatusEnum.Confirm.getId()){
				List<OtaCheckInUser> userList = checkInUserDAO.findOtaCheckInUsers(returnOrder.getId());
				room.put("userList", userList);
//			}
			JSONArray jInUsers = new JSONArray();
			if (showInUser && room.get("userList") != null) {
				for (OtaCheckInUser inUser : (List<OtaCheckInUser>) room.get("userList")) {
					JSONObject jInUser = new JSONObject();
					jInUser.put("cpname", inUser.get("name"));
					jInUser.put("cpsex", inUser.get("sex", ""));
					jInUser.put("ethnic", inUser.get("ethnic", ""));
					jInUser.put("birthday", inUser.get("birthday", ""));
					jInUser.put("cardtype", inUser.get("cardtype", ""));
					jInUser.put("cardid", inUser.get("cardid", ""));
					jInUser.put("address", inUser.get("address", ""));
					jInUser.put("phone", inUser.get("phone", ""));
					jInUsers.add(jInUser);
				}
				jsonRoom.put("checkinuser", jInUsers);
			}
			roomOrder.add(jsonRoom);
		}// end of loop otaroomorders

		jsonObj.put("roomorder", roomOrder);

	}

	/**
	 * 重写优惠券实际优惠价格
	 */
	private BigDecimal rewritePromotionprice(BigDecimal totalprice,BigDecimal promotionprice,Long activeid){
		BigDecimal realCost = BigDecimal.ZERO;//用户实际支付价格  
		if(activeid!=null){
			if(Constant.ACTIVE_10YUAN_1 == activeid){
				realCost = BigDecimal.ONE;
			}else if(Constant.ACTIVE_15YUAN == activeid){
				realCost = new BigDecimal(15);
			}
			if(Constant.ACTIVE_15YUAN == activeid || Constant.ACTIVE_10YUAN_1 == activeid){
				//总价153 优惠券150 实际支付4元 优惠149元
				if (totalprice.subtract(promotionprice).compareTo(BigDecimal.ZERO) > 0) {
					realCost =  totalprice.subtract(promotionprice).add(realCost);
			    }
				//总价149 优惠券150 实际支付1元 优惠148元
				return totalprice.subtract(realCost);
			}
		}
		return promotionprice;
	}
	
	
	public THotel getThotelThreadLocal(Long hotelId) {
		Map hotels = (Map) ThreadUtil.threadVar("KEY_THOTEL");
		hotels = hotels == null ? Maps.newHashMap() : hotels;
		if (hotels.containsKey(hotelId)) {
			return (THotel) hotels.get(hotelId);
		} else {
			HotelService hotelService = AppUtils.getBean(HotelService.class);
			THotel hotel = hotelService.readonlyTHotel(hotelId);
			hotels.put(hotelId, hotel);
			ThreadUtil.setThreadVar("KEY_THOTEL", hotels);
			return hotel;
		}
	}

	/**
	 * Json转换为联系人 图片保存
	 * 
	 * @param checkInUser
	 * @return
	 * @throws Exception
	 */
	public static List<OtaCheckInUser> getInUsersByJson(String checkInUser) throws Exception {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
		if (StringUtils.isNotBlank(checkInUser)) {
			List<OtaCheckInUser> inUsers = new ArrayList<>();
			JSONArray jsonArr = JSON.parseArray(checkInUser);
			for (int i = 0; i < jsonArr.size(); i++) {
				JSONObject jsonObj = jsonArr.getJSONObject(i);
				OtaCheckInUser inUser = new OtaCheckInUser();
				inUser.set("name", jsonObj.getString("name"));
				for (SexEnum sexEnum : SexEnum.values()) {
					if (sexEnum.getId().equals(jsonObj.getString("sex"))) {
						inUser.set("Sex", sexEnum.getId());
						throw MyErrorEnum.errorParm.getMyException();
					}
				}
				Ethnic ethnic = new Ethnic();
				ethnic.setId(jsonObj.getString("ethnic"));
				inUser.set("ethnic", ethnic);
				inUser.set("birthday", jsonObj.getString("birthday"));
				for (CardTypeEnum cardType : CardTypeEnum.values()) {
					if (cardType.getId().equals(jsonObj.getString("cardType"))) {
						inUser.set("cardtype", cardType);
						break;
					}
				}
				inUser.set("cardid", jsonObj.getString("cardid"));
				inUser.set("disid", jsonObj.getLong("disid"));
				inUser.set("address", jsonObj.getString("address"));
				inUser.set("phone", jsonObj.getString("phone"));// 电话
				// 照片保存到FTP里 用ImgBase64Util
				File file = File.createTempFile("card" + UUID.randomUUID(), "jpg");
				// TODO: 待处理
				// //ImgBase64Util.generateImage(jsonObj.getString("img"),
				// file);
				// //FileManager.getInstance().uploadUserCardPic(file);

				inUsers.add(inUser);
			}
			return inUsers;
		}
		return null;
	}

	/**
	 * 拆分返回联系人
	 * 
	 * @param quickUserId id用,隔开
	 * @return
	 */
	public static List<OtaCheckInUser> getInUsersByUserId(String quickUserId) {
		if (StringUtils.isNotBlank(quickUserId)) {
			List<OtaCheckInUser> inUsers = new ArrayList<>();
			String[] quickUserIds = quickUserId.split(",");
			for (String uId : quickUserIds) {
				OtaCheckInUser quickUser = new OtaCheckInUser();
				quickUser.set("id", Long.parseLong(uId));
				inUsers.add(quickUser);
			}
			return inUsers;
		}
		return null;
	}

	/**
	 * 获取一个月前的日期
	 * 
	 * @return
	 */
	public static Long getLimitDateTime() {
		Calendar cal = Calendar.getInstance();
		// TODO: 待处理
		int limitDays = Integer.parseInt(SysConfig.getInstance().getSysValueByKey(Constant.qiekeLimitDays).trim());
		cal.set(Calendar.DAY_OF_MONTH, cal.get(Calendar.DAY_OF_MONTH) - limitDays);
		return cal.getTime().getTime();
	}

	public static String getQiekeStartTime() {
		Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+8"));
		int hour = cal.get(Calendar.HOUR_OF_DAY);
		cal.set(Calendar.MINUTE, 0);
		cal.set(Calendar.SECOND, 0);
		cal.set(Calendar.MILLISECOND, 0);
		cal.set(Calendar.HOUR_OF_DAY, 6);
		if (hour >= BORDERLINEHOUR) {
			return DateUtils.getDatetime(cal.getTimeInMillis());
		}
		cal.set(Calendar.DATE, cal.get(Calendar.DATE) - 1);
		return DateUtils.getDatetime(cal.getTimeInMillis());
	}

	public static JSONArray getJsonByTicketInfo(List<TicketInfo> infos) {
		JSONArray jsonArr = new JSONArray();
		for (TicketInfo ticketInfo : infos) {
			JSONObject jsonObj = new JSONObject();
			jsonObj.put("id", ticketInfo.getId());
			jsonObj.put("name", ticketInfo.getName());
			jsonObj.put("select", ticketInfo.isSelect() ? "T" : "F");
			jsonObj.put("check", ticketInfo.getCheck() ? "T" : "F");
			jsonObj.put("subprice", ticketInfo.getSubprice());
			jsonObj.put("offlinesubprice", ticketInfo.getOfflineprice());
			jsonObj.put("type", ticketInfo.getType());
			jsonObj.put("isticket", ticketInfo.getIsticket() ? "T" : "F");
			jsonObj.put("begintime",
					ticketInfo.getBegintime() != null ? DateUtils.getStringFromDate(ticketInfo.getBegintime(), DateUtils.FORMATDATETIME) : "");
			jsonObj.put("endtime", ticketInfo.getEndtime() != null ? DateUtils.getStringFromDate(ticketInfo.getEndtime(), DateUtils.FORMATDATETIME)
					: "");
			jsonObj.put("activityid", ticketInfo.getActivityid());
			jsonObj.put("uselimit", ticketInfo.getUselimit());
			jsonArr.add(jsonObj);
		}
		return jsonArr;
	}

	private static void defaultTicketSelect(List<TicketInfo> ticketInfos) {
		// 判断第一张是否可用
		if (ticketInfos == null || ticketInfos.size() < 1) {
			return;
		}
		TicketInfo ticketInfo = ticketInfos.get(0);
		if (ticketInfo.getCheck()) {
			ticketInfo.setSelect(true);
		}
	}
	
	
	public StringBuffer getRequestParamStrings(HttpServletRequest request) {
		Enumeration<String> enu = request.getParameterNames();
		StringBuffer str = new StringBuffer();
		while (enu.hasMoreElements()) {
			String paraName = (String) enu.nextElement();
			str.append(paraName).append(":").append(request.getParameter(paraName)).append(",");
		}
		str.setLength(str.length() - 1);
		return str;
	}
	
	public boolean checkNotNulls(HttpServletRequest request, String[] notnulls){
		StringBuffer s = new StringBuffer();
		StringBuffer _s = new StringBuffer();
		for (String k : notnulls) {
			_s.append(k).append(":").append(request.getParameter(k)).append(",");
			if (StringUtils.isEmpty(request.getParameter(k))) {
				s.append(k).append(",");
			}
		}
		if (s.length() > 0) {
			s.setLength(s.length() - 1);
			logger.info("缺少必填项："+s.toString());
		}
		logger.info("OTSMessage::createOrder::checkNotNulls"+_s.toString());
		return s.length() > 0;
	}
}