package com.mk.ots.roomsale.service.impl;

import com.mk.ots.common.utils.DateTools;
import com.mk.ots.hotel.service.HotelService;
import com.mk.ots.mapper.RoomSaleForPmsMapper;
import com.mk.ots.mapper.RoomSaleMapper;
import com.mk.ots.roomsale.model.*;
import com.mk.ots.roomsale.service.RoomSaleForPmsService;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * RoomSaleMapper.
 * 
 * @author kangxiaolong.
 */
@Service
public class RoomSaleForPmsServiceImpl implements RoomSaleForPmsService {
	private Logger logger = org.slf4j.LoggerFactory.getLogger(RoomSaleForPmsServiceImpl.class);

	@Autowired
	private RoomSaleMapper roomSaleMapper;
	@Autowired
	private HotelService hotelService;
	@Autowired
	private RoomSaleForPmsMapper roomSaleForPmsMapper;
	public Boolean updateTRoomSaleConfig(TRoomSaleConfigForPms bean){
		if (bean.getRoomTypeId()==null&&bean.getNewCount()==null){
			return false;
		}
		TRoomSaleConfig roomSaleConfig=roomSaleForPmsMapper.getRoomTypeByPms(bean.getRoomTypeId());
		if(roomSaleConfig==null){
			return  false;
		}
		TRoomSaleConfig newConfig =new TRoomSaleConfig();
		newConfig.setRoomTypeId(roomSaleConfig.getId());
		newConfig.setValid("T");
		List<TRoomSaleConfig> roomSaleConfigList=roomSaleForPmsMapper.queryRoomSaleConfigByParams(newConfig);
		if(CollectionUtils.isEmpty(roomSaleConfigList)){
			return  false;
		}
		TRoomSaleConfig configToUpdate=roomSaleConfigList.get(0);
		if (bean.getNewCount()<=configToUpdate.getDealCount()){
			return  false;
		}
		configToUpdate.setNum(bean.getNewCount());
		Integer result= roomSaleForPmsMapper.updateRoomSaleNum(configToUpdate);
		if (result>0){
			return  true;
		}else{
			return  false;
		}
	}
	public TRoomSaleForPms getHotelRoomSale(TRoomSaleConfigForPms bean){
		if (bean.getHotelId()==null){
			return null;
		}
		List<TRoomSaleConfig> roomSaleConfigList=roomSaleForPmsMapper.getRoomSaleByPmsHotel(bean.getHotelId());
		if(CollectionUtils.isEmpty(roomSaleConfigList)){
			return null;
		}
		List<TRoomSalePms> roomSalePmsList= roomSaleForPmsMapper.getRoomSalePms();
		String confShow ="";
		String showBegin="";
		long showContinue=0;
		String noConfShow="";
		for (TRoomSalePms roomSalePms:roomSalePmsList){
			if (roomSalePms.getType()==1){
				confShow=roomSalePms.getText();
				showBegin=roomSalePms.getShowBegin();
				showContinue=roomSalePms.getShowContinue();
			}else if(roomSalePms.getType()==2){
				noConfShow=roomSalePms.getText();
			}
		}

		List<TRoomTypeForPms> roomTypeForPmsList=new ArrayList<>();
		TRoomSaleForPms roomSaleForPms=new TRoomSaleForPms();
		roomSaleForPms.setConfShow(confShow);
		roomSaleForPms.setShowBegin(showBegin);
		roomSaleForPms.setShowContinue(showContinue);
		roomSaleForPms.setNoConfShow(noConfShow);
		int i=0;
		for (TRoomSaleConfig roomSaleConfig:roomSaleConfigList){
			if (i==0) {
				roomSaleForPms.setType(Integer.valueOf(roomSaleConfig.getPromoType()));
				roomSaleForPms.setBegin(DateTools.dateToString(roomSaleConfig.getStartTime(), "HH:mm:ss"));
				Long continues = getBetweenTime(roomSaleConfig.getStartTime(), roomSaleConfig.getEndTime());
				roomSaleForPms.setContinues(continues);
				i++;
			}
			TRoomSaleConfig roomType=roomSaleForPmsMapper.getRoomTypeById(roomSaleConfig.getRoomTypeId());
			TRoomTypeForPms roomTypeForPms=new TRoomTypeForPms();
			roomTypeForPms.setRoomTypeId(roomType.getPms());
			roomTypeForPms.setCurrCount(roomSaleConfig.getNum());
			roomTypeForPms.setMinCount(roomSaleConfig.getDealCount());
			roomTypeForPmsList.add(roomTypeForPms);
		}
		roomSaleForPms.setInfo(roomTypeForPmsList);
		return roomSaleForPms;
	}
	public long getBetweenTime(Date beginTime, Date endTime) {
		Calendar dateOne = Calendar.getInstance(), dateTwo = Calendar.getInstance();
		dateOne.setTime(beginTime);
		dateTwo.setTime(endTime);
		if (beginTime.compareTo(endTime)>=0){
			dateTwo.add(Calendar.DATE,1);
		}
		long timeOne = dateOne.getTimeInMillis();
		long timeTwo = dateTwo.getTimeInMillis();
		long minute = (timeTwo - timeOne) / (1000 * 60);//转化minute
		return minute;
	}
}

