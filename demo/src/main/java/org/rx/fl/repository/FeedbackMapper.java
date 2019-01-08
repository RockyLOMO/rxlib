package org.rx.fl.repository;

import org.rx.fl.repository.model.Feedback;
import org.rx.fl.repository.model.FeedbackExample;

/**
 * FeedbackMapper继承基类
 */
public interface FeedbackMapper extends MyBatisBaseDao<Feedback, String, FeedbackExample> {
}