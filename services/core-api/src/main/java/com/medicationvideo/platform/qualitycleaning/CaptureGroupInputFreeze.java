package com.medicationvideo.platform.qualitycleaning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.medicationvideo.platform.assetcapture.CaptureGroupReceiptState;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;

/**
 * CaptureGroup 接入事实到「标注输入快照」的纯投影。
 *
 * <p>
 * 这是人工标注、复核与导出共用的唯一准入依据：机位是否可标注完全由探针事实推导
 * （{@code present && asset_available && media_status = INGESTED}），不要求任何人工质量确认动作。
 * 人工对机位的唯一否决入口是机位处置 {@code EXCLUDED}（必填原因 + 审计）。
 *
 * <p>
 * 快照字段沿用 {@code capture-group-view-set.v1} 既有形状，其中 {@code quality_review_decision}
 * 自本版本起表示**探针推导的可标注判定** （{@code QUALIFIED} = 可标注，{@code REJECTED} = 不可标注），不再读取人工质量评审。
 */
public final class CaptureGroupInputFreeze {

	public static final String SCHEMA_VERSION = "capture-group-view-set.v1";

	/** 机位可标注：探针已确认媒体可用。 */
	public static final String ANNOTATABLE = "QUALIFIED";

	/** 机位不可标注：缺失、资产不可用或媒体未入库。 */
	public static final String NOT_ANNOTATABLE = "REJECTED";

	private static final String INPUT_VERSION_PREFIX = SCHEMA_VERSION + ":";

	private CaptureGroupInputFreeze() {
	}

	/** 按当前接入事实计算输入快照；不读写任何持久化状态。 */
	public static Snapshot of(CaptureGroupFacts facts) {
		var root = new LinkedHashMap<String, Object>();
		root.put("schema_version", SCHEMA_VERSION);
		root.put("capture_group_id", facts.captureGroupId().toString());
		root.put("capture_group_version", facts.captureGroupVersion());
		root.put("camera_set_snapshot_id", facts.cameraSetSnapshotId().toString());
		root.put("receipt_state", facts.receiptState().name());
		root.put("views", facts.views().stream().map(CaptureGroupInputFreeze::viewSnapshot).toList());
		var viewSetSha256 = digest(facts);
		var annotatableViewCount = (int) facts.views().stream().filter(CaptureGroupInputFreeze::annotatable).count();
		return new Snapshot(Collections.unmodifiableMap(root), INPUT_VERSION_PREFIX + viewSetSha256, viewSetSha256,
				facts.receiptState().name(), facts.views().size(), annotatableViewCount);
	}

	/** 机位能否进入人工标注：只看探针事实，不看任何人工质量结论。 */
	public static boolean annotatable(CaptureGroupView view) {
		return view.present() && view.assetAvailable() && "INGESTED".equals(view.mediaStatus());
	}

	private static Map<String, Object> viewSnapshot(CaptureGroupView view) {
		var snapshot = new LinkedHashMap<String, Object>();
		snapshot.put("camera_number", view.cameraNumber());
		snapshot.put("camera_stream_id", view.cameraStreamId() == null ? null : view.cameraStreamId().toString());
		snapshot.put("clip_asset_id", view.clipAssetId() == null ? null : view.clipAssetId().toString());
		snapshot.put("camera_channel_id", view.cameraChannelId() == null ? null : view.cameraChannelId().toString());
		snapshot.put("present", view.present());
		snapshot.put("media_status", view.mediaStatus());
		snapshot.put("technical_quality_status", view.technicalQualityStatus());
		snapshot.put("stream_version", view.streamVersion());
		snapshot.put("clip_asset_version", view.clipAssetVersion());
		snapshot.put("asset_available", view.assetAvailable());
		snapshot.put("quality_review_decision", annotatable(view) ? ANNOTATABLE : NOT_ANNOTATABLE);
		snapshot.put("quality_review_version", 0);
		return snapshot;
	}

	private static String digest(CaptureGroupFacts facts) {
		try {
			var canonical = new StringBuilder();
			append(canonical, facts.captureGroupId());
			append(canonical, facts.captureGroupVersion());
			append(canonical, facts.cameraSetSnapshotId());
			append(canonical, facts.receiptState());
			for (var view : facts.views()) {
				append(canonical, view.cameraNumber());
				append(canonical, view.cameraStreamId());
				append(canonical, view.clipAssetId());
				append(canonical, view.cameraChannelId());
				append(canonical, view.present());
				append(canonical, view.mediaStatus());
				append(canonical, view.technicalQualityStatus());
				append(canonical, view.streamVersion());
				append(canonical, view.clipAssetVersion());
				append(canonical, view.assetAvailable());
				append(canonical, annotatable(view) ? ANNOTATABLE : NOT_ANNOTATABLE);
			}
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static void append(StringBuilder target, Object value) {
		var text = value == null ? "" : value.toString();
		target.append(text.length()).append(':').append(text).append('\n');
	}

	/** 冻结输入快照：内容由客观事实决定，可安全地在多个读点重复计算。 */
	public record Snapshot(Map<String, Object> confirmedViewSet, String inputVersion, String viewSetSha256,
			String receiptState, int viewCount, int annotatableViewCount) {

		/** 是否允许进入人工标注：接收已定稿且至少存在一个可标注机位。 */
		public boolean admitting() {
			return this.annotatableViewCount > 0
					&& !CaptureGroupReceiptState.RECEIVING.name().equals(this.receiptState);
		}

		/** 机位是否可参与复核：全机位均可标注即无排除项。 */
		public boolean fullyAnnotatable() {
			return this.viewCount > 0 && this.annotatableViewCount == this.viewCount;
		}
	}

}
