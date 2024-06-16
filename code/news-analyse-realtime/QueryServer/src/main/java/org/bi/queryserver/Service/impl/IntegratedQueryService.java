package org.bi.queryserver.Service.impl;


import org.bi.queryserver.DAO.HBaseDAO;
import org.bi.queryserver.DAO.MySQLDAO;
import org.bi.queryserver.DAO.RedisDAO;
import org.bi.queryserver.Domain.Clicks;
import org.bi.queryserver.Domain.NewsInfo;
import org.bi.queryserver.Service.IIntegratedQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class IntegratedQueryService implements IIntegratedQueryService {

    @Autowired
    HBaseDAO hbaseDAO;

    @Autowired
    MySQLDAO mysqlDAO;

    @Autowired
    RedisDAO redisDAO;

    @Autowired
    NewsService newsService;


    /**
     * @param userIDs
     * @param newsCategories
     * @param titleMinLen    = 0
     * @param titleMaxLen
     * @param bodyMinLen     = 0
     * @param bodyMaxLen
     */
    public List<Clicks> integratedQuery(String[] userIDs,
                                        String[] newsCategories,
                                        String startTime,
                                        String endTime,
                                        int titleMinLen,
                                        int titleMaxLen,
                                        int bodyMinLen,
                                        int bodyMaxLen) throws Exception {

        // 按理来说应该是一开始有所有的News ID,然后慢慢筛选，再统计点击量
        // 但这样的响应时间可能会有些过于大了，因此限制必须要选择


        // 通过用户ID获取的点击过的新闻ID集合
        Set<String> userIDFilterSet = new HashSet<String>();



        for (String userID : userIDs) {
            List<String> newsIDs = newsService.getClickedNewsIDsByUserID(
                    userID,
                    startTime,
                    endTime);

            for (String newsID : newsIDs) {
                userIDFilterSet.add(newsID);
            }
        }



        // 通过种类获取的点击过的新闻ID集合
        Set<String> categoryFilterSet = new HashSet<>();
        for (String category : newsCategories) {
            List<String> newsIDs = newsService.getClickedNewsIDsByCategory(
                    category,
                    startTime,
                    endTime
            );

            for (String newsID : newsIDs) {
                categoryFilterSet.add(newsID);
            }
        }


        // 目标新闻ID集合
        Set<String> newsIDSet = new HashSet<>();

        if (userIDFilterSet.isEmpty() && categoryFilterSet.isEmpty()) {
            // 所有新闻点击记录，过于庞大，暂且不考虑
        } else if (categoryFilterSet.isEmpty()) {
            newsIDSet.addAll(userIDFilterSet);
        } else if (userIDFilterSet.isEmpty()) {
            newsIDSet.addAll(categoryFilterSet);
        } else {
            newsIDSet.addAll(categoryFilterSet);
            newsIDSet.retainAll(userIDFilterSet);
        }



        // 获取新闻信息的列表
        List<String> newsIDs = new ArrayList<>(newsIDSet);
        List<NewsInfo> newsInfos = newsService.getNewsInfo(newsIDs);

        // 通过标题长度和内容长度进行筛选
        for (NewsInfo newsInfo : newsInfos) {
            if(newsInfo.getHeadlineLen() > titleMaxLen || newsInfo.getNewsBodyLen() > bodyMaxLen){
                newsIDSet.remove(newsInfo.getNewsID());
            }
        }

        /**
        弃用代码，把多线程封装在getNewsInfo中

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        // 通过标题长度和内容长度进行筛选
        for (String newsID : newsIDSet) {
            executor.submit(() -> {
                NewsInfo newsInfo = null;
                try {
                    newsInfo = newsService.getNewsInfo(newsID);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                if (newsInfo != null) {
                    if (newsInfo.getHeadlineLen() > titleMaxLen) {
                        newsIDSet.remove(newsID);
                    }

                    if (newsInfo.getNewsBodyLen() > bodyMaxLen) {
                        newsIDSet.remove(newsID);
                    }
                }
            });
        }
        // 关闭线程池并等待所有任务完成
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
         *
         */




        long st = System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        // 统计剩下新闻的点击量
        for (String newsID : newsIDSet) {
            executor.submit(() -> {
                List<Clicks> newsClicks = null;
                try {
                    newsClicks = newsService.getClicksHistory(
                            newsID,
                            startTime,
                            endTime
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        long et = System.currentTimeMillis() - st;
        System.out.println("Integrated query took " + et + " ms");


        return new ArrayList<>();
    }


}
