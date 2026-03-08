package com.anontokyo.lottery.rpc.activity.booth.res;

import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.rpc.activity.booth.dto.AwardDTO;

import java.io.Serializable;

/**
 * @description: 抽奖响应对象
 * @author：anontokyo
 * @date: 2026/1/25
 */
public class DrawRes implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 统一响应结果（包含状态码和信息） */
    private Result result;

    /** 奖品信息 */
    private AwardDTO awardDTO;

    public DrawRes() {
    }

    public DrawRes(Result result) {
        this.result = result;
    }

    public DrawRes(Result result, AwardDTO awardDTO) {
        this.result = result;
        this.awardDTO = awardDTO;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }

    public AwardDTO getAwardDTO() {
        return awardDTO;
    }

    public void setAwardDTO(AwardDTO awardDTO) {
        this.awardDTO = awardDTO;
    }

}
