package com.lmax.disruptor.example;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventPoller;
import com.lmax.disruptor.RingBuffer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 提供一个类似多级缓存模式，先从本地缓存获取，获取不到才从RingBuffer获取数据，每次或取所有有效数据并保存到本地缓存
 * 然后获取数据是从本地获取
 * Alternative usage of EventPoller, here we wrap it around BatchedEventPoller
 * to achieve Disruptor's batching. this speeds up the polling feature
 */
public class PullWithBatchedPoller {
    public static void main(String[] args) throws Exception {
        int batchSize = 40;
        RingBuffer<BatchedPoller.DataEvent<Object>> ringBuffer = RingBuffer.createMultiProducer(BatchedPoller.DataEvent.factory(), 1024);

        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                //每个线程都需要自己维护一个poller
                BatchedPoller<Object> poller = new BatchedPoller<Object>(ringBuffer, batchSize);

                while (true){

                    Object value = null;
                    try {
                        value = poller.poll();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    // Value could be null if no events are available.
                    if (null != value) {
                        // Process value.
                        System.out.println("The thread number is " + Thread.currentThread().getName() + " for consumer data " + value);
                    }else {
                        try {
                            TimeUnit.MILLISECONDS.sleep(1_000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }

            }).start();
        }

        TimeUnit.MILLISECONDS.sleep(2_00);

        for (int i = 0; i < 20; i++) {
            //发布数据
            long sequence = ringBuffer.next();

            BatchedPoller.DataEvent event = ringBuffer.get(sequence);
            event.data = i;

            ringBuffer.publish(sequence);
        }

        TimeUnit.MILLISECONDS.sleep(50_000);

    }
}

class BatchedPoller<T> {

    private final EventPoller<BatchedPoller.DataEvent<T>> poller;
    private final int maxBatchSize;
    private final BatchedData<T> polledData;

    BatchedPoller(RingBuffer<BatchedPoller.DataEvent<T>> ringBuffer, int batchSize) {
        this.poller = ringBuffer.newPoller();
        //设置消费者Sequence
        ringBuffer.addGatingSequences(poller.getSequence());

        if (batchSize < 1) {
            batchSize = 20;
        }
        this.maxBatchSize = batchSize;
        this.polledData = new BatchedData<T>(this.maxBatchSize);
    }

    public T poll() throws Exception {
        //判断本地是否有数据
        if (polledData.getMsgCount() > 0) {
            return polledData.pollMessage(); // we just fetch from our local
        }
        //从RingBuffer获取数据，每次获取所有有效数据
        loadNextValues(poller, polledData); // we try to load from the ring
        //本地存在数据直接拉取本地数据
        return polledData.getMsgCount() > 0 ? polledData.pollMessage() : null;
    }

    private EventPoller.PollState loadNextValues(EventPoller<BatchedPoller.DataEvent<T>> poller, final BatchedData<T> batch)
            throws Exception {
        //开始从RingBuffer获取数据
        return poller.poll((event, sequence, endOfBatch) -> {
            T item = event.copyOfData();
            //添加到本地缓存
            return item != null ? batch.addDataItem(item) : false;
        });
    }

    public static class DataEvent<T> {

        T data;

        public static <T> EventFactory<BatchedPoller.DataEvent<T>> factory() {
            return () -> new DataEvent<T>();
        }

        public T copyOfData() {
            // Copy the data out here. In this case we have a single reference
            // object, so the pass by
            // reference is sufficient. But if we were reusing a byte array,
            // then we
            // would need to copy
            // the actual contents.
            return data;
        }

        void set(T d) {
            data = d;
        }

    }

    /***
     *@className BatchedPoller
     *
     *@description 封装本地数据相关操作
     *
     *@author <a href="http://youngitman.tech">青年IT男</a>
     *
     *@date 18:12 2020-02-06
     *
     *@JunitTest: {@link  }
     *
     *@version v1.0.0
     *
    **/
    private static class BatchedData<T> {

        private int msgHighBound;
        private final int capacity;
        private final T[] data;
        private int cursor;

        @SuppressWarnings("unchecked")
        BatchedData(int size) {
            this.capacity = size;
            data = (T[]) new Object[this.capacity];
        }

        private void clearCount() {
            msgHighBound = 0;
            cursor = 0;
        }

        public int getMsgCount() {
            return msgHighBound - cursor;
        }

        /***
         *
         * 添加到本地缓存中
         *
         * @author liyong
         * @date 23:29 2020-02-04
         * @param item
         * @exception
         * @return boolean
         **/
        public boolean addDataItem(T item) throws IndexOutOfBoundsException {
            if (msgHighBound >= capacity) {
                throw new IndexOutOfBoundsException("Attempting to add item to full batch");
            }

            data[msgHighBound++] = item;
            return msgHighBound < capacity;
        }

        /***
         *
         * 获取本地数据
         *
         * @author liyong
         * @date 23:28 2020-02-04
         * @param
         * @exception
         * @return T
         **/
        public T pollMessage() {
            T rtVal = null;
            if (cursor < msgHighBound) {
                rtVal = data[cursor++];
            }
            //重置两个指针位置
            if (cursor > 0 && cursor >= msgHighBound) {
                clearCount();
            }
            return rtVal;
        }
    }
}
