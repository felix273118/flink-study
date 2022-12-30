package com.zhisheng.examples.demo;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.aggregation.Aggregations;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.util.Collector;

public class Demo1 {

	public static void main(String[] args) throws Exception {
		// 获取执行环境 ExecutionEnvironment （批处理用这个对象）
		final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
		// 配置服务地址以及【当前jar所在路径】
		// final ExecutionEnvironment env =
		// ExecutionEnvironment.createRemoteEnvironment("10.51.114.3", 8081,
		// "D:\\work\\flink-learning\\flink-learning-examples\\target\\flink-learning-examples-1.0-SNAPSHOT.jar");

		// 加载数据源到 DataSet
		DataSet<String> text = env.readTextFile("/rpa/test.txt");
		DataSet<Tuple2<String, Integer>> counts = text.flatMap(new FlatMapFunction<String, Tuple2<String, Integer>>() {
			@Override
			public void flatMap(String s, Collector<Tuple2<String, Integer>> collector) throws Exception {
				// s 即从文本中读取到一行字符串，按空格分割后得到数组tokens
				String[] tokens = s.toLowerCase().split("\\s+");
				for (String token : tokens) {
					if (token.length() > 0) {
						// 初始化每一个单词，保存为元祖对象
						collector.collect(new Tuple2<String, Integer>(token, 1));
					}
				}
			}
			// 0表示Tuple2<String, Integer>
			// 中的第一个元素，即分割后的单词
			// 同理，1表示Tuple2<String, Integer> 中的第二个元素，即出现次数
		}).groupBy(0).aggregate(Aggregations.SUM, 1);

		counts.print();
	}
}
