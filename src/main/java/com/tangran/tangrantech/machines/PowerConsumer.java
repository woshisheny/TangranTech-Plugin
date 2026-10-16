package com.tangran.tangrantech.machines;

/** 耗电机器：暴露每次合成/每刻的耗电量，供展示 lore 与能量网络使用。 */
public interface PowerConsumer {

    /** 每次操作（或每刻）消耗的电量，单位 J。 */
    int getConsumption();
}
