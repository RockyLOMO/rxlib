package org.rx.lr.repository.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.repository.DataObject;

import java.math.BigDecimal;

/**
 * 产品
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Product extends DataObject {
String imgUrl;
String name;
String enName;
String define;

BigDecimal price;
Integer unit;
Integer minNum;
    Integer maxNum;
    Boolean isHot;

   String title;

}
