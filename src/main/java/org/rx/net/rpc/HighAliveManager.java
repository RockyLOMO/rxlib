//package org.rx.net.rpc;
//
//import lombok.RequiredArgsConstructor;
//import org.rx.bean.RandomList;
//import org.rx.net.rpc.impl.StatefulRpcClient;
//import org.rx.util.function.BiAction;
//
//import static org.rx.core.App.combine;
//
//@RequiredArgsConstructor
//public class HighAliveManager<T> implements BiAction<StatefulRpcClient> {
//    final RandomList<T> randomList;
//    volatile int weight;
//
//    @Override
//    public void invoke(StatefulRpcClient client) throws Throwable {
//        client.onConnected = combine(client.onConnected, (s, e) -> {
//            weight = 10;
//        });
//        client.onPong = combine(client.onPong, (s, e) -> {
//            long latency = Math.max(10, e.getValue().getReplyTimestamp() - e.getValue().getTimestamp());
//            weight = (int) latency;
//        });
//        client.onDisconnected = combine(client.onDisconnected, (s, e) -> {
//            weight = 0;
//        });
//    }
//}
