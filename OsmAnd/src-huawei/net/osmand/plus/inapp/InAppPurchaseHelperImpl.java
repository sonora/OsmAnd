package net.osmand.plus.inapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.huawei.hms.iap.Iap;
import com.huawei.hms.iap.IapClient;
import com.huawei.hms.iap.entity.InAppPurchaseData;
import com.huawei.hms.iap.entity.IsEnvReadyResult;
import com.huawei.hms.iap.entity.OrderStatusCode;
import com.huawei.hms.iap.entity.OwnedPurchasesResult;
import com.huawei.hms.iap.entity.ProductInfo;
import com.huawei.hms.iap.entity.ProductInfoResult;
import com.huawei.hms.iap.entity.PurchaseIntentResult;
import com.huawei.hms.iap.entity.PurchaseResultInfo;

import net.osmand.AndroidUtils;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.inapp.InAppPurchases.InAppPurchase;
import net.osmand.plus.inapp.InAppPurchases.InAppSubscription;
import net.osmand.plus.inapp.InAppPurchases.InAppSubscriptionIntroductoryInfo;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.backend.OsmandSettings.OsmandPreference;
import net.osmand.util.Algorithms;

import java.lang.ref.WeakReference;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InAppPurchaseHelperImpl extends InAppPurchaseHelper {

	private boolean envReady = false;
	private boolean purchaseSupported = false;

	private List<ProductInfo> productInfos;
	private OwnedPurchasesResult ownedSubscriptions;
	private List<OwnedPurchasesResult> ownedInApps = new ArrayList<>();

	public InAppPurchaseHelperImpl(OsmandApplication ctx) {
		super(ctx);
		purchases = new InAppPurchasesImpl(ctx);
	}

	@Override
	public void isInAppPurchaseSupported(@NonNull final Activity activity, @Nullable final InAppPurchaseInitCallback callback) {
		if (envReady) {
			if (callback != null) {
				if (purchaseSupported) {
					callback.onSuccess();
				} else {
					callback.onFail();
				}
			}
		} else {
			// Initiating an isEnvReady request when entering the app.
			// Check if the account service country supports IAP.
			IapClient mClient = Iap.getIapClient(activity);
			final WeakReference<Activity> activityRef = new WeakReference<>(activity);
			IapRequestHelper.isEnvReady(mClient, new IapApiCallback<IsEnvReadyResult>() {

				private void onReady(boolean succeed) {
					logDebug("Setup finished.");
					envReady = true;
					purchaseSupported = succeed;
					if (callback != null) {
						if (succeed) {
							callback.onSuccess();
						} else {
							callback.onFail();
						}
					}
				}

				@Override
				public void onSuccess(IsEnvReadyResult result) {
					onReady(true);
				}

				@Override
				public void onFail(Exception e) {
					onReady(false);
					LOG.error("isEnvReady fail, " + e.getMessage(), e);
					ExceptionHandle.handle(activityRef.get(), e);
				}
			});
		}
	}

	protected void execImpl(@NonNull final InAppPurchaseTaskType taskType, @NonNull final InAppCommand command) {
		if (envReady) {
			command.run(this);
		} else {
			command.commandDone();
		}
	}

	private InAppCommand getPurchaseInAppCommand(@NonNull final Activity activity, @NonNull final String productId) throws UnsupportedOperationException {
		return new InAppCommand() {
			@Override
			public void run(InAppPurchaseHelper helper) {
				try {
					ProductInfo productInfo = getProductInfo(productId);
					IapRequestHelper.createPurchaseIntent(getIapClient(), productInfo.getProductId(),
							IapClient.PriceType.IN_APP_NONCONSUMABLE, new IapApiCallback<PurchaseIntentResult>() {
								@Override
								public void onSuccess(PurchaseIntentResult result) {
									if (result == null) {
										logError("result is null");
									} else {
										// you should pull up the page to complete the payment process
										IapRequestHelper.startResolutionForResult(activity, result.getStatus(), Constants.REQ_CODE_BUY_INAPP);
									}
									commandDone();
								}

								@Override
								public void onFail(Exception e) {
									int errorCode = ExceptionHandle.handle(activity, e);
									if (errorCode != ExceptionHandle.SOLVED) {
										logDebug("createPurchaseIntent, returnCode: " + errorCode);
										if (OrderStatusCode.ORDER_PRODUCT_OWNED == errorCode) {
											logError("already own this product");
										} else {
											logError("unknown error");
										}
									}
									commandDone();
								}
							});
				} catch (Exception e) {
					complain("Cannot launch full version purchase!");
					logError("purchaseFullVersion Error", e);
					stop(true);
				}
			}
		};
	}

	@Override
	public void purchaseFullVersion(@NonNull final Activity activity) throws UnsupportedOperationException {
		notifyShowProgress(InAppPurchaseTaskType.PURCHASE_FULL_VERSION);
		exec(InAppPurchaseTaskType.PURCHASE_FULL_VERSION, getPurchaseInAppCommand(activity, ""));
	}

	@Override
	public void purchaseDepthContours(@NonNull final Activity activity) throws UnsupportedOperationException {
		notifyShowProgress(InAppPurchaseTaskType.PURCHASE_DEPTH_CONTOURS);
		exec(InAppPurchaseTaskType.PURCHASE_DEPTH_CONTOURS, getPurchaseInAppCommand(activity, ""));
	}

	@Nullable
	private ProductInfo getProductInfo(@NonNull String productId) {
		List<ProductInfo> productInfos = this.productInfos;
		if (productInfos != null) {
			for (ProductInfo info : productInfos) {
				if (info.getProductId().equals(productId)) {
					return info;
				}
			}
		}
		return null;
	}

	private boolean hasDetails(@NonNull String productId) {
		return getProductInfo(productId) != null;
	}

	@Nullable
	private InAppPurchaseData getPurchaseData(@NonNull String productId) {
		InAppPurchaseData data = SubscriptionUtils.getPurchaseData(ownedSubscriptions, productId);
		if (data == null) {
			for (OwnedPurchasesResult result : ownedInApps) {
				data = InAppUtils.getPurchaseData(result, productId);
				if (data != null) {
					break;
				}
			}
		}
		return data;
	}

	private PurchaseInfo getPurchaseInfo(InAppPurchaseData purchase) {
		return new PurchaseInfo(purchase.getProductId(), purchase.getOrderID(), purchase.getPurchaseToken());
	}

	private void fetchInAppPurchase(@NonNull InAppPurchase inAppPurchase, @NonNull ProductInfo productInfo, @Nullable InAppPurchaseData purchaseData) {
		if (purchaseData != null) {
			inAppPurchase.setPurchaseState(InAppPurchase.PurchaseState.PURCHASED);
			inAppPurchase.setPurchaseTime(purchaseData.getPurchaseTime());
		} else {
			inAppPurchase.setPurchaseState(InAppPurchase.PurchaseState.NOT_PURCHASED);
		}
		inAppPurchase.setPrice(productInfo.getPrice());
		inAppPurchase.setPriceCurrencyCode(productInfo.getCurrency());
		if (productInfo.getMicrosPrice() > 0) {
			inAppPurchase.setPriceValue(productInfo.getMicrosPrice() / 1000000d);
		}
		String subscriptionPeriod = productInfo.getSubPeriod();
		if (!Algorithms.isEmpty(subscriptionPeriod)) {
			if (inAppPurchase instanceof InAppSubscription) {
				try {
					((InAppSubscription) inAppPurchase).setSubscriptionPeriodString(subscriptionPeriod);
				} catch (ParseException e) {
					LOG.error(e);
				}
			}
		}
		if (inAppPurchase instanceof InAppSubscription) {
			String introductoryPrice = productInfo.getSubSpecialPrice();
			String introductoryPricePeriod = productInfo.getSubSpecialPeriod();
			int introductoryPriceCycles = productInfo.getSubSpecialPeriodCycles();
			long introductoryPriceAmountMicros = productInfo.getSubSpecialPriceMicros();
			if (!Algorithms.isEmpty(introductoryPrice)) {
				InAppSubscription s = (InAppSubscription) inAppPurchase;
				try {
					s.setIntroductoryInfo(new InAppSubscriptionIntroductoryInfo(s, introductoryPrice,
							introductoryPriceAmountMicros, introductoryPricePeriod, String.valueOf(introductoryPriceCycles)));
				} catch (ParseException e) {
					LOG.error(e);
				}
			}
		}
	}

	protected InAppCommand getPurchaseLiveUpdatesCommand(final WeakReference<Activity> activity, final String sku, final String payload) {
		return new InAppCommand() {
			@Override
			public void run(InAppPurchaseHelper helper) {
				try {
					Activity a = activity.get();
					ProductInfo productInfo = getProductInfo(sku);
					if (AndroidUtils.isActivityNotDestroyed(a) && productInfo != null) {
						IapRequestHelper.createPurchaseIntent(getIapClient(), sku,
								IapClient.PriceType.IN_APP_SUBSCRIPTION, payload, new IapApiCallback<PurchaseIntentResult>() {
									@Override
									public void onSuccess(PurchaseIntentResult result) {
										if (result == null) {
											logError("GetBuyIntentResult is null");
										} else {
											Activity a = activity.get();
											if (AndroidUtils.isActivityNotDestroyed(a)) {
												IapRequestHelper.startResolutionForResult(a, result.getStatus(), Constants.REQ_CODE_BUY_SUB);
											} else {
												logError("startResolutionForResult on destroyed activity");
											}
										}
										commandDone();
									}

									@Override
									public void onFail(Exception e) {
										int errorCode = ExceptionHandle.handle(activity.get(), e);
										if (ExceptionHandle.SOLVED != errorCode) {
											logError("createPurchaseIntent, returnCode: " + errorCode);
											if (OrderStatusCode.ORDER_PRODUCT_OWNED == errorCode) {
												logError("already own this product");
											} else {
												logError("unknown error");
											}
										}
										commandDone();
									}
								});
					} else {
						stop(true);
					}
				} catch (Exception e) {
					logError("launchPurchaseFlow Error", e);
					stop(true);
				}
			}
		};
	}

	@Override
	protected InAppCommand getRequestInventoryCommand() {
		return new InAppCommand() {

			@Override
			public void run(InAppPurchaseHelper helper) {
				logDebug("Setup successful. Querying inventory.");
				try {
					productInfos = new ArrayList<>();
					if (uiActivity != null) {
						obtainOwnedSubscriptions();
					} else {
						commandDone();
					}
				} catch (Exception e) {
					logError("queryInventoryAsync Error", e);
					notifyDismissProgress(InAppPurchaseTaskType.REQUEST_INVENTORY);
					stop(true);
					commandDone();
				}
			}

			private void obtainOwnedSubscriptions() {
				if (uiActivity != null) {
					IapRequestHelper.obtainOwnedPurchases(getIapClient(), IapClient.PriceType.IN_APP_SUBSCRIPTION,
							null, new IapApiCallback<OwnedPurchasesResult>() {
								@Override
								public void onSuccess(OwnedPurchasesResult result) {
									ownedSubscriptions = result;
									obtainOwnedInApps(null);
								}

								@Override
								public void onFail(Exception e) {
									logError("obtainOwnedSubscriptions exception", e);
									ExceptionHandle.handle((Activity) uiActivity, e);
									commandDone();
								}
							});
				} else {
					commandDone();
				}
			}

			private void obtainOwnedInApps(final String continuationToken) {
				// Query users' purchased non-consumable products.
				IapRequestHelper.obtainOwnedPurchases(getIapClient(), IapClient.PriceType.IN_APP_NONCONSUMABLE,
						continuationToken, new IapApiCallback<OwnedPurchasesResult>() {
							@Override
							public void onSuccess(OwnedPurchasesResult result) {
								ownedInApps.add(result);
								if (result != null && !TextUtils.isEmpty(result.getContinuationToken())) {
									obtainOwnedInApps(result.getContinuationToken());
								} else {
									obtainSubscriptionsInfo();
								}
							}

							@Override
							public void onFail(Exception e) {
								logError("obtainOwnedInApps exception", e);
								ExceptionHandle.handle((Activity) uiActivity, e);
								commandDone();
							}
						});

			}

			private void obtainSubscriptionsInfo() {
				Set<String> productIds = new HashSet<>();
				List<InAppSubscription> subscriptions = purchases.getLiveUpdates().getAllSubscriptions();
				for (InAppSubscription s : subscriptions) {
					productIds.add(s.getSku());
				}
				productIds.addAll(ownedSubscriptions.getItemList());
				IapRequestHelper.obtainProductInfo(getIapClient(), new ArrayList<>(productIds),
						IapClient.PriceType.IN_APP_SUBSCRIPTION, new IapApiCallback<ProductInfoResult>() {
							@Override
							public void onSuccess(final ProductInfoResult result) {
								if (result == null) {
									logError("obtainSubscriptionsInfo: ProductInfoResult is null");
									commandDone();
									return;
								}
								productInfos.addAll(result.getProductInfoList());
								obtainInAppsInfo();
							}

							@Override
							public void onFail(Exception e) {
								int errorCode = ExceptionHandle.handle((Activity) uiActivity, e);
								if (ExceptionHandle.SOLVED != errorCode) {
									LOG.error("Unknown error");
								}
								commandDone();
							}
						});
			}

			private void obtainInAppsInfo() {
				Set<String> productIds = new HashSet<>();
				for (InAppPurchase purchase : getInAppPurchases().getAllInAppPurchases(false)) {
					productIds.add(purchase.getSku());
				}
				for (OwnedPurchasesResult result : ownedInApps) {
					productIds.addAll(result.getItemList());
				}
				IapRequestHelper.obtainProductInfo(getIapClient(), new ArrayList<>(productIds),
						IapClient.PriceType.IN_APP_NONCONSUMABLE, new IapApiCallback<ProductInfoResult>() {
							@Override
							public void onSuccess(ProductInfoResult result) {
								if (result == null || result.getProductInfoList() == null) {
									logError("obtainInAppsInfo: ProductInfoResult is null");
									commandDone();
									return;
								}
								productInfos.addAll(result.getProductInfoList());

								processInventory();
								commandDone();
							}

							@Override
							public void onFail(Exception e) {
								int errorCode = ExceptionHandle.handle((Activity) uiActivity, e);
								if (ExceptionHandle.SOLVED != errorCode) {
									LOG.error("Unknown error");
								}
								commandDone();
							}
						});
			}

			private void processInventory() {
				logDebug("Query sku details was successful.");

				/*
				 * Check for items we own. Notice that for each purchase, we check
				 * the developer payload to see if it's correct!
				 */

				List<String> allOwnedSubscriptionSkus = ownedSubscriptions.getItemList();
				for (InAppSubscription s : getLiveUpdates().getAllSubscriptions()) {
					if (hasDetails(s.getSku())) {
						InAppPurchaseData purchaseData = getPurchaseData(s.getSku());
						ProductInfo liveUpdatesInfo = getProductInfo(s.getSku());
						if (liveUpdatesInfo != null) {
							fetchInAppPurchase(s, liveUpdatesInfo, purchaseData);
						}
						allOwnedSubscriptionSkus.remove(s.getSku());
					}
				}
				for (String sku : allOwnedSubscriptionSkus) {
					InAppPurchaseData purchaseData = getPurchaseData(sku);
					ProductInfo liveUpdatesInfo = getProductInfo(sku);
					if (liveUpdatesInfo != null) {
						InAppSubscription s = getLiveUpdates().upgradeSubscription(sku);
						if (s == null) {
							s = new InAppPurchaseLiveUpdatesOldSubscription(liveUpdatesInfo);
						}
						fetchInAppPurchase(s, liveUpdatesInfo, purchaseData);
					}
				}

				InAppPurchase fullVersion = getFullVersion();
				if (hasDetails(fullVersion.getSku())) {
					InAppPurchaseData purchaseData = getPurchaseData(fullVersion.getSku());
					ProductInfo fullPriceDetails = getProductInfo(fullVersion.getSku());
					if (fullPriceDetails != null) {
						fetchInAppPurchase(fullVersion, fullPriceDetails, purchaseData);
					}
				}

				InAppPurchase depthContours = getDepthContours();
				if (hasDetails(depthContours.getSku())) {
					InAppPurchaseData purchaseData = getPurchaseData(depthContours.getSku());
					ProductInfo depthContoursDetails = getProductInfo(depthContours.getSku());
					if (depthContoursDetails != null) {
						fetchInAppPurchase(depthContours, depthContoursDetails, purchaseData);
					}
				}

				InAppPurchase contourLines = getContourLines();
				if (hasDetails(contourLines.getSku())) {
					InAppPurchaseData purchaseData = getPurchaseData(contourLines.getSku());
					ProductInfo contourLinesDetails = getProductInfo(contourLines.getSku());
					if (contourLinesDetails != null) {
						fetchInAppPurchase(contourLines, contourLinesDetails, purchaseData);
					}
				}

				InAppPurchaseData fullVersionPurchase = getPurchaseData(fullVersion.getSku());
				boolean fullVersionPurchased = fullVersionPurchase != null;
				if (fullVersionPurchased) {
					ctx.getSettings().FULL_VERSION_PURCHASED.set(true);
				}

				InAppPurchaseData depthContoursPurchase = getPurchaseData(depthContours.getSku());
				boolean depthContoursPurchased = depthContoursPurchase != null;
				if (depthContoursPurchased) {
					ctx.getSettings().DEPTH_CONTOURS_PURCHASED.set(true);
				}

				// Do we have the live updates?
				boolean subscribedToLiveUpdates = false;
				List<InAppPurchaseData> liveUpdatesPurchases = new ArrayList<>();
				for (InAppPurchase p : getLiveUpdates().getAllSubscriptions()) {
					InAppPurchaseData purchaseData = getPurchaseData(p.getSku());
					if (purchaseData != null) {
						liveUpdatesPurchases.add(purchaseData);
						if (!subscribedToLiveUpdates) {
							subscribedToLiveUpdates = true;
						}
					}
				}
				OsmandPreference<Long> subscriptionCancelledTime = ctx.getSettings().LIVE_UPDATES_PURCHASE_CANCELLED_TIME;
				if (!subscribedToLiveUpdates && ctx.getSettings().LIVE_UPDATES_PURCHASED.get()) {
					if (subscriptionCancelledTime.get() == 0) {
						subscriptionCancelledTime.set(System.currentTimeMillis());
						ctx.getSettings().LIVE_UPDATES_PURCHASE_CANCELLED_FIRST_DLG_SHOWN.set(false);
						ctx.getSettings().LIVE_UPDATES_PURCHASE_CANCELLED_SECOND_DLG_SHOWN.set(false);
					} else if (System.currentTimeMillis() - subscriptionCancelledTime.get() > SUBSCRIPTION_HOLDING_TIME_MSEC) {
						ctx.getSettings().LIVE_UPDATES_PURCHASED.set(false);
						if (!isDepthContoursPurchased(ctx)) {
							ctx.getSettings().getCustomRenderBooleanProperty("depthContours").set(false);
						}
					}
				} else if (subscribedToLiveUpdates) {
					subscriptionCancelledTime.set(0L);
					ctx.getSettings().LIVE_UPDATES_PURCHASED.set(true);
				}

				lastValidationCheckTime = System.currentTimeMillis();
				logDebug("User " + (subscribedToLiveUpdates ? "HAS" : "DOES NOT HAVE")
						+ " live updates purchased.");

				OsmandSettings settings = ctx.getSettings();
				settings.INAPPS_READ.set(true);

				List<InAppPurchaseData> tokensToSend = new ArrayList<>();
				if (liveUpdatesPurchases.size() > 0) {
					List<String> tokensSent = Arrays.asList(settings.BILLING_PURCHASE_TOKENS_SENT.get().split(";"));
					for (InAppPurchaseData purchase : liveUpdatesPurchases) {
						if ((Algorithms.isEmpty(settings.BILLING_USER_ID.get()) || Algorithms.isEmpty(settings.BILLING_USER_TOKEN.get()))
								&& !Algorithms.isEmpty(purchase.getDeveloperPayload())) {
							String payload = purchase.getDeveloperPayload();
							if (!Algorithms.isEmpty(payload)) {
								String[] arr = payload.split(" ");
								if (arr.length > 0) {
									settings.BILLING_USER_ID.set(arr[0]);
								}
								if (arr.length > 1) {
									token = arr[1];
									settings.BILLING_USER_TOKEN.set(token);
								}
							}
						}
						if (!tokensSent.contains(purchase.getProductId())) {
							tokensToSend.add(purchase);
						}
					}
				}
				List<PurchaseInfo> purchaseInfoList = new ArrayList<>();
				for (InAppPurchaseData purchase : tokensToSend) {
					purchaseInfoList.add(getPurchaseInfo(purchase));
				}
				onSkuDetailsResponseDone(purchaseInfoList);
			}
		};
	}

	private IapClient getIapClient() {
		return Iap.getIapClient((Activity) uiActivity);
	}

	// Call when a purchase is finished
	private void onPurchaseFinished(InAppPurchaseData purchase) {
		logDebug("Purchase finished: " + purchase.getProductId());
		onPurchaseDone(getPurchaseInfo(purchase));
	}

	@Override
	protected boolean isBillingManagerExists() {
		return false;
	}

	@Override
	protected void destroyBillingManager() {
		// non implemented
	}

	@Override
	public boolean onActivityResult(@NonNull Activity activity, int requestCode, int resultCode, Intent data) {
		if (requestCode == Constants.REQ_CODE_BUY_SUB) {
			boolean succeed = false;
			if (resultCode == Activity.RESULT_OK) {
				PurchaseResultInfo result = SubscriptionUtils.getPurchaseResult(activity, data);
				if (result != null) {
					if (OrderStatusCode.ORDER_STATE_SUCCESS == result.getReturnCode()) {
						InAppPurchaseData purchaseData = SubscriptionUtils.getInAppPurchaseData(null,
								result.getInAppPurchaseData(), result.getInAppDataSignature());
						if (purchaseData != null) {
							onPurchaseFinished(purchaseData);
							succeed = true;
						} else {
							logDebug("Purchase failed");
						}
					} else if (OrderStatusCode.ORDER_STATE_CANCEL == result.getReturnCode()) {
						logDebug("Purchase cancelled");
					}
				} else {
					logDebug("Purchase failed");
				}
			} else {
				logDebug("Purchase cancelled");
			}
			if (!succeed) {
				stop(true);
			}
			return true;
		} else if (requestCode == Constants.REQ_CODE_BUY_INAPP) {
			boolean succeed = false;
			if (data == null) {
				logDebug("data is null");
			} else {
				PurchaseResultInfo buyResultInfo = Iap.getIapClient(activity).parsePurchaseResultInfoFromIntent(data);
				switch (buyResultInfo.getReturnCode()) {
					case OrderStatusCode.ORDER_STATE_CANCEL:
						logDebug("Order has been canceled");
						break;
					case OrderStatusCode.ORDER_PRODUCT_OWNED:
						logDebug("Product already owned");
						break;
					case OrderStatusCode.ORDER_STATE_SUCCESS:
						InAppPurchaseData purchaseData = InAppUtils.getInAppPurchaseData(null,
								buyResultInfo.getInAppPurchaseData(), buyResultInfo.getInAppDataSignature());
						if (purchaseData != null) {
							onPurchaseFinished(purchaseData);
							succeed = true;
						} else {
							logDebug("Purchase failed");
						}
						break;
					default:
						break;
				}
			}
			if (!succeed) {
				stop(true);
			}
			return true;
		}
		return false;
	}
}
