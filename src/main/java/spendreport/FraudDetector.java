/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spendreport;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.walkthrough.common.entity.Alert;
import org.apache.flink.walkthrough.common.entity.Transaction;

/**
 * 业务逻辑
 */
public class FraudDetector extends KeyedProcessFunction<Long, Transaction, Alert> {

	private static final long serialVersionUID = 1L;

	private static final double SMALL_AMOUNT = 1.00;
	private static final double LARGE_AMOUNT = 500.00;
	private static final long ONE_MINUTE = 60 * 1000;

	// ValueState 的作用域始终限于当前的 key
	private transient ValueState<Boolean> flagState;
	private transient ValueState<Long> timerState;

	@Override
	public void open(Configuration parameters) {
		ValueStateDescriptor<Boolean> flagDescriptor = new ValueStateDescriptor<>(
				"flag",
				Types.BOOLEAN);
		flagState = getRuntimeContext().getState(flagDescriptor);

		ValueStateDescriptor<Long> timerDescriptor = new ValueStateDescriptor<>(
				"timer-state",
				Types.LONG);
		timerState = getRuntimeContext().getState(timerDescriptor);
	}

	@Override
	public void processElement(
			Transaction transaction,
			Context context,
			Collector<Alert> collector) throws Exception {

		// 获取当前交易的状态
		Boolean lastTransactionWasSmall = flagState.value();

		// 判断之前已经被设置为true过
		// 只使用了 unset (null) 和 true 两种来判断标记状态被设置了与否
		if (lastTransactionWasSmall != null) {
			if (transaction.getAmount() > LARGE_AMOUNT) {
				// 当前用户为欺诈账号
				Alert alert = new Alert();
				alert.setId(transaction.getAccountId());

				collector.collect(alert);
			}

			// 在检查之后，不论是什么状态，都需要被清空
			cleanUp(context);
		}

		// 最后，检查当前交易的金额是否属于小额交易。 如果是，那么需要设置标记状态，以便可以在下一个事件中对其进行检查
		if (transaction.getAmount() < SMALL_AMOUNT) {
			// Set the flag to true
			flagState.update(true);

			// 设置定时器,1分钟后触发该定时器,清除flagState状态和定时器,这样1分钟后实施的大额行为就不会被判定为欺诈
			long timer = context.timerService().currentProcessingTime() + ONE_MINUTE;
			context.timerService().registerProcessingTimeTimer(timer);
			timerState.update(timer);
		}
	}

	// 当定时器触发时，将会调用 KeyedProcessFunction#onTimer 方法。 通过重写这个方法来实现一个你自己的重置状态的回调逻辑。
	// // 设置定时器,1分钟后触发该定时器,清除flagState状态和定时器,这样1分钟后实施的大额行为就不会被判定为欺诈
	@Override
	public void onTimer(long timestamp, OnTimerContext ctx, Collector<Alert> out) {
		// remove flag after 1 minute
		timerState.clear();
		flagState.clear();
	}

	// 当判定为欺诈时,状态和时间都得清空,为下一个判定做准备
	private void cleanUp(Context ctx) throws Exception {
		// delete timer
		Long timer = timerState.value();
		ctx.timerService().deleteProcessingTimeTimer(timer);

		// clean up all state
		timerState.clear();
		flagState.clear();
	}
}
