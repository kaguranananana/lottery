package cn.itedus.lottery.rpc.activity.booth.req;

import java.io.Serializable;
import java.util.Map;

/**
 * @description: 量化人群抽奖请求对象
 * @author：anontokyo
 * @date: 2026/1/25
 * 
 *        用于规则引擎量化人群决策，根据用户画像匹配活动
 */
public class QuantificationDrawReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private String uId;

    /** 规则树ID */
    private Long treeId;

    /** 决策因子（如 gender=man, age=22） */
    private Map<String, Object> valMap;

    public QuantificationDrawReq() {
    }

    public QuantificationDrawReq(String uId, Long treeId, Map<String, Object> valMap) {
        this.uId = uId;
        this.treeId = treeId;
        this.valMap = valMap;
    }

    public String getuId() {
        return uId;
    }

    public void setuId(String uId) {
        this.uId = uId;
    }

    public Long getTreeId() {
        return treeId;
    }

    public void setTreeId(Long treeId) {
        this.treeId = treeId;
    }

    public Map<String, Object> getValMap() {
        return valMap;
    }

    public void setValMap(Map<String, Object> valMap) {
        this.valMap = valMap;
    }

}
