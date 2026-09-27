package com.evops.photo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 边界情况只读统计：去年同期租车单/交接照片极端值（rental-photo-1）。
 * 历史交接照片只留了逗号分隔地址串，照片张数按逗号数估算（空白串记 0）。
 * SQL 别名加双引号保留小写，H2 返回 Map 时键名才稳定为下划线小写。
 */
@Mapper
public interface PhotoBoundaryMapper {

    String PHOTO_REF_EXPR =
            "(CASE WHEN TRIM(h.damage_photos) = '' THEN 0"
            + " ELSE LENGTH(h.damage_photos) - LENGTH(REPLACE(h.damage_photos, ',', '')) + 1 END)";

    /**
     * 去年同期窗口内交接记录聚合：
     * 照片引用总数/单交接最多张数/地址串最长字符数/油量极值。
     */
    @Select("SELECT COUNT(*) AS \"handover_count\","
            + " COALESCE(SUM(CASE WHEN TRIM(damage_photos) = '' THEN 0"
            + "   ELSE LENGTH(damage_photos) - LENGTH(REPLACE(damage_photos, ',', '')) + 1 END), 0)"
            + "   AS \"photo_refs\","
            + " COALESCE(MAX(CASE WHEN TRIM(damage_photos) = '' THEN 0"
            + "   ELSE LENGTH(damage_photos) - LENGTH(REPLACE(damage_photos, ',', '')) + 1 END), 0)"
            + "   AS \"max_photo_refs\","
            + " COALESCE(MAX(LENGTH(damage_photos)), 0) AS \"max_photos_length\","
            + " MIN(fuel_reading) AS \"fuel_min\","
            + " MAX(fuel_reading) AS \"fuel_max\""
            + " FROM t_rental_handover"
            + " WHERE biz_time >= #{from} AND biz_time < #{to}")
    Map<String, Object> handoverAggregate(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /** 同期订单数与结算金额极值。 */
    @Select("SELECT COUNT(*) AS \"order_count\","
            + " MIN(settle_amount) AS \"settle_min\","
            + " MAX(settle_amount) AS \"settle_max\""
            + " FROM t_rental_order"
            + " WHERE biz_time >= #{from} AND biz_time < #{to}")
    Map<String, Object> orderAggregate(@Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    /** 照片张数最多的若干交接（极端单样本，便于人工核对当初是怎么传的）。 */
    @Select("SELECT h.id AS \"handover_id\", h.order_id AS \"order_id\","
            + " o.order_no AS \"order_no\", h.type AS \"handover_type\","
            + " h.fuel_reading AS \"fuel_reading\","
            + " LENGTH(h.damage_photos) AS \"photos_length\","
            + " " + PHOTO_REF_EXPR + " AS \"photo_refs\""
            + " FROM t_rental_handover h"
            + " JOIN t_rental_order o ON o.id = h.order_id"
            + " WHERE h.biz_time >= #{from} AND h.biz_time < #{to}"
            + " ORDER BY \"photo_refs\" DESC, h.id ASC"
            + " LIMIT #{limit}")
    List<Map<String, Object>> topPhotoHandovers(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to,
                                                @Param("limit") int limit);

    /** 新链路上传照片实测字节/尺寸/处理耗时极值（上线后用于回头校准限额）。 */
    @Select("SELECT COUNT(*) AS \"photo_count\","
            + " COALESCE(MAX(original_size), 0) AS \"max_original_size\","
            + " COALESCE(MAX(original_width), 0) AS \"max_original_width\","
            + " COALESCE(MAX(original_height), 0) AS \"max_original_height\","
            + " COALESCE(MAX(processing_ms), 0) AS \"max_processing_ms\""
            + " FROM t_handover_photo"
            + " WHERE biz_time >= #{from} AND biz_time < #{to}")
    Map<String, Object> photoAggregate(@Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);
}
