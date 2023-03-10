package com.zhisheng.examples.streaming.watermark;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.time.Time;

/**
 * Desc: allowedLateness
 * Created by zhisheng on 2019-07-07
 * blog：http://www.54tianzhisheng.cn/
 * 微信公众号：zhisheng
 */
public class Main3 {
    public static void main(String[] args) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        //并行度设置为 1
        env.setParallelism(1);
//        env.setParallelism(4);

        SingleOutputStreamOperator<Word> data = env.socketTextStream("localhost", 9001)
                .map(new MapFunction<String, Word>() {
                    @Override
                    public Word map(String value) throws Exception {
                        String[] split = value.split(",");
                        return new Word(split[0], Integer.valueOf(split[1]), Long.valueOf(split[2]));
                    }
                }).assignTimestampsAndWatermarks(new WordPeriodicWatermark());

        data.keyBy(0)
                .timeWindow(Time.seconds(10))
                .allowedLateness(Time.milliseconds(2))
                .sum(1)
                .print();

        env.execute("watermark demo");
    }
}
