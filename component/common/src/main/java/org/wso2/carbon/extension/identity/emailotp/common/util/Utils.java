/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.com).
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.extension.identity.emailotp.common.util;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.extension.identity.emailotp.common.constant.Constants;
import org.wso2.carbon.extension.identity.emailotp.common.dto.ConfigsDTO;
import org.wso2.carbon.extension.identity.emailotp.common.exception.EmailOtpClientException;
import org.wso2.carbon.extension.identity.emailotp.common.exception.EmailOtpException;
import org.wso2.carbon.extension.identity.emailotp.common.exception.EmailOtpServerException;
import org.wso2.carbon.extension.identity.emailotp.common.internal.EmailOtpServiceDataHolder;
import org.wso2.carbon.identity.application.authentication.framework.exception.FrameworkException;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.event.IdentityEventConfigBuilder;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.bean.ModuleConfiguration;
import org.wso2.carbon.identity.governance.IdentityGovernanceException;
import org.wso2.carbon.identity.handler.event.account.lock.exception.AccountLockServiceException;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.common.User;

import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.ACCOUNT_DISABLED_CLAIM_URI;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.ResidentIdpPropertyName.ACCOUNT_DISABLE_HANDLER_ENABLE_PROPERTY;
import static org.wso2.carbon.identity.handler.event.account.lock.constants.AccountConstants.ACCOUNT_LOCKED_PROPERTY;
import static org.wso2.carbon.identity.handler.event.account.lock.constants.AccountConstants.ACCOUNT_UNLOCK_TIME_PROPERTY;
import static org.wso2.carbon.identity.handler.event.account.lock.constants.AccountConstants.FAILED_LOGIN_ATTEMPTS_PROPERTY;
import static org.wso2.carbon.identity.handler.event.account.lock.constants.AccountConstants.LOGIN_FAIL_TIMEOUT_RATIO_PROPERTY;

/**
 * Util functions for Email OTP service.
 */
public class Utils {

    private static final Log log = LogFactory.getLog(Utils.class);

    /**
     * Read configurations and populate ConfigsDTO object.
     *
     * @throws EmailOtpServerException Throws upon an issue while reading configs.
     */
    public static void readConfigurations() throws EmailOtpServerException {

        Properties properties;
        try {
            ModuleConfiguration configs = IdentityEventConfigBuilder.getInstance()
                    .getModuleConfigurations(Constants.EMAIL_OTP_IDENTITY_EVENT_MODULE_NAME);
            if (configs != null) {
                properties = configs.getModuleProperties();
            } else {
                properties = new Properties();
                if (log.isDebugEnabled()) {
                    log.debug("Couldn't find Email OTP handler configurations.");
                }
            }
            sanitizeAndPopulateConfigs(properties);
        } catch (IdentityEventException e) {
            throw Utils.handleServerException(Constants.ErrorMessage.SERVER_EVENT_CONFIG_LOADING_ERROR,
                    Constants.EMAIL_OTP_IDENTITY_EVENT_MODULE_NAME, e);
        }
        log.debug(String.format("Email OTP service configurations : %s.",
                EmailOtpServiceDataHolder.getConfigs().toString()));
    }

    /**
     * Sanitize the configurations and apply the default configs if the configs are not presented in the configurations.
     *
     * @param properties pass the properties.
     * @throws EmailOtpServerException Throws upon an issue while populating configs.
     */
    private static void sanitizeAndPopulateConfigs(Properties properties) throws EmailOtpServerException {

        ConfigsDTO configs = EmailOtpServiceDataHolder.getConfigs();

        boolean isEnabled = Boolean.parseBoolean(StringUtils.trim(
                properties.getProperty(Constants.EMAIL_OTP_ENABLED)));
        configs.setEnabled(isEnabled);

        // Defaults to 'false'.
        boolean triggerNotification = Boolean.parseBoolean(StringUtils.trim(
                properties.getProperty(Constants.EMAIL_OTP_TRIGGER_OTP_NOTIFICATION)));
        configs.setTriggerNotification(triggerNotification);

        boolean showFailureReason = Boolean.parseBoolean(StringUtils.trim(
                properties.getProperty(Constants.EMAIL_OTP_SHOW_FAILURE_REASON)));
        configs.setShowFailureReason(showFailureReason);

        boolean isAlphaNumericOtp = Boolean.parseBoolean(StringUtils.trim(
                properties.getProperty(Constants.EMAIL_OTP_ALPHA_NUMERIC_OTP)));
        configs.setAlphaNumericOTP(isAlphaNumericOtp);

        String otpLengthValue = StringUtils.trim(properties.getProperty(
                Constants.EMAIL_OTP_LENGTH));
        int otpLength = StringUtils.isNumeric(otpLengthValue) ?
                Integer.parseInt(otpLengthValue) : Constants.DEFAULT_OTP_LENGTH;
        configs.setOtpLength(otpLength);

        String otpValidityPeriodValue =
                StringUtils.trim(properties.getProperty(Constants.EMAIL_OTP_VALIDITY_PERIOD));
        int otpValidityPeriod = StringUtils.isNumeric(otpValidityPeriodValue) ?
                Integer.parseInt(otpValidityPeriodValue) * 1000 : Constants.DEFAULT_EMAIL_OTP_EXPIRY_TIME;
        configs.setOtpValidityPeriod(otpValidityPeriod);

        boolean isEnableMultipleSessions = Boolean.parseBoolean(StringUtils.trim(
                properties.getProperty(Constants.EMAIL_OTP_MULTIPLE_SESSIONS_ENABLED)));
        configs.setEnableMultipleSessions(isEnableMultipleSessions);

        boolean lockAccountOnFailedAttempts = Boolean.parseBoolean(StringUtils.trim(
                properties.getProperty(Constants.EMAIL_OTP_LOCK_ACCOUNT_ON_FAILED_ATTEMPTS)));
        configs.setLockAccountOnFailedAttempts(lockAccountOnFailedAttempts);

        // If not defined, defaults to 'zero' to renew always.
        String otpRenewIntervalValue = StringUtils.trim(
                properties.getProperty(Constants.EMAIL_OTP_RENEWAL_INTERVAL));
        int otpRenewalInterval = StringUtils.isNumeric(otpRenewIntervalValue) ?
                Integer.parseInt(otpRenewIntervalValue) * 1000 : 0;
        configs.setOtpRenewalInterval(otpRenewalInterval);

        if (otpRenewalInterval >= otpValidityPeriod) {
            throw Utils.handleServerException(Constants.ErrorMessage.SERVER_INVALID_RENEWAL_INTERVAL_ERROR,
                    String.valueOf(otpRenewalInterval));
        }

        String otpResendThrottleIntervalValue = StringUtils.trim(
                properties.getProperty(Constants.EMAIL_OTP_RESEND_THROTTLE_INTERVAL));
        int resendThrottleInterval = StringUtils.isNumeric(otpResendThrottleIntervalValue) ?
                Integer.parseInt(otpResendThrottleIntervalValue) * 1000 :
                Constants.DEFAULT_EMAIL_RESEND_THROTTLE_INTERVAL;
        configs.setResendThrottleInterval(resendThrottleInterval);

        // Should we send the same OTP upon the next generation request? Defaults to 'false'.
        boolean resendSameOtp = (otpRenewalInterval > 0) && (otpRenewalInterval < otpValidityPeriod);
        configs.setResendSameOtp(resendSameOtp);

        // Defaults to 'true' with an interval of 30 seconds.
        boolean resendThrottlingEnabled = resendThrottleInterval > 0;
        configs.setResendThrottlingEnabled(resendThrottlingEnabled);
    }

    /**
     * This is to hash a text in SHA256 algorithm.
     *
     * @param text Text that need to be hashed.
     * @return Encoded hash.
     */
    public static String getHash(String text, String text2) {

        return DigestUtils.sha256Hex(text + text2);
    }

    public static String getHash(String text) {

        return DigestUtils.sha256Hex(text);
    }

    /**
     * Generate a random transaction id for each transaction.
     *
     * @return Random generated UUID for to clearly identifies a transaction.
     */
    public static String createTransactionId() {

        String transactionId = UUID.randomUUID().toString();
        if (log.isDebugEnabled()) {
            log.debug(String.format("Transaction Id hash: %s.", transactionId.hashCode()));
        }
        return transactionId;
    }

    /**
     * This is to handle client exceptions.
     *
     * @param error Error message.
     * @param data  Error data.
     * @return EmailOtpClientException.
     */
    public static EmailOtpClientException handleClientException(Constants.ErrorMessage error, String data) {

        String description;
        if (StringUtils.isNotBlank(data)) {
            description = String.format(error.getDescription(), data);
        } else {
            description = error.getDescription();
        }
        return new EmailOtpClientException(error.getMessage(), description, error.getCode());
    }

    /**
     * This is to handle client exceptions.
     *
     * @param error Error message.
     * @param data  Error data.
     * @param e     Thrown exception.
     * @return EmailOtpClientException.
     */
    public static EmailOtpClientException handleClientException(Constants.ErrorMessage error, String data,
                                                                Throwable e) {

        String description;
        if (StringUtils.isNotBlank(data)) {
            description = String.format(error.getDescription(), data);
        } else {
            description = error.getDescription();
        }
        return new EmailOtpClientException(error.getMessage(), description, error.getCode(), e);
    }

    /**
     * This is to handle server exceptions.
     *
     * @param error Error message.
     * @param data  Error data.
     * @param e     Thrown exception.
     * @return EmailOtpServerException.
     */
    public static EmailOtpServerException handleServerException(Constants.ErrorMessage error, String data,
                                                                Throwable e) {

        String description;
        if (StringUtils.isNotBlank(data)) {
            description = String.format(error.getDescription(), data);
        } else {
            description = error.getDescription();
        }
        return new EmailOtpServerException(error.getMessage(), description, error.getCode(), e);
    }

    /**
     * This is to handle server exceptions.
     *
     * @param error Error message.
     * @param data  Error data.
     * @return EmailOtpServerException.
     */
    public static EmailOtpServerException handleServerException(Constants.ErrorMessage error, String data) {

        String description;
        if (StringUtils.isNotBlank(data)) {
            description = String.format(error.getDescription(), data);
        } else {
            description = error.getDescription();
        }
        return new EmailOtpServerException(error.getMessage(), description, error.getCode());
    }

    /**
     * Check whether a given user is locked.
     *
     * @param user The user.
     * @return True if user account is locked.
     */
    public static boolean isAccountLocked(User user) throws EmailOtpServerException {

        try {
            if (user == null) {
                return false;
            }
            return EmailOtpServiceDataHolder.getInstance().getAccountLockService().isAccountLocked(user.getUsername(),
                    user.getTenantDomain(), user.getUserStoreDomain());
        } catch (AccountLockServiceException e) {
            throw Utils.handleServerException(Constants.ErrorMessage.SERVER_ERROR_VALIDATING_ACCOUNT_LOCK_STATUS,
                    user.getUserID(), e);
        }
    }

    public static boolean isUserDisabled(User user) throws EmailOtpException {

        try {
            if (!isAccountDisablingEnabled(user.getTenantDomain())) {
                return false;
            }
            String accountDisabledClaimValue = getClaimValue(
                    user.getUserID(), ACCOUNT_DISABLED_CLAIM_URI, user.getTenantDomain());
            return Boolean.parseBoolean(accountDisabledClaimValue);
        } catch (FrameworkException e) {
            throw new EmailOtpException(e.getErrorCode(), e.getMessage(), e);
        }
    }

    private static boolean isAccountDisablingEnabled(String tenantDomain) throws FrameworkException {

        Property accountDisableConfigProperty = FrameworkUtils.getResidentIdpConfiguration(
                ACCOUNT_DISABLE_HANDLER_ENABLE_PROPERTY, tenantDomain);

        return accountDisableConfigProperty != null && Boolean.parseBoolean(accountDisableConfigProperty.getValue());
    }

    private static String getClaimValue(String userId, String claimURI, String tenantDomain) throws
            FrameworkException {

        try {
            int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
            AbstractUserStoreManager userStoreManager = (AbstractUserStoreManager) EmailOtpServiceDataHolder
                    .getInstance().getRealmService().getTenantUserRealm(tenantId).getUserStoreManager();

            Map<String, String> values = userStoreManager.getUserClaimValuesWithID(userId, new String[]{claimURI},
                    UserCoreConstants.DEFAULT_PROFILE);
            if (log.isDebugEnabled()) {
                log.debug(String.format("%s claim value of user %s is set to: " + values.get(claimURI),
                        claimURI, userId));
            }
            return values.get(claimURI);

        } catch (UserStoreException e) {
            throw new FrameworkException("Error occurred while retrieving claim: " + claimURI, e);
        }
    }

    /**
     * Get the account lock connector configurations.
     *
     * @param tenantDomain Tenant domain.
     * @return Account lock connector configurations.
     * @throws EmailOtpServerException Server exception while retrieving account lock configurations.
     */
    public static Property[] getAccountLockConnectorConfigs(String tenantDomain) throws EmailOtpServerException {

        try {
            return EmailOtpServiceDataHolder.getInstance().getIdentityGovernanceService().getConfiguration
                    (new String[]{ACCOUNT_LOCKED_PROPERTY, FAILED_LOGIN_ATTEMPTS_PROPERTY, ACCOUNT_UNLOCK_TIME_PROPERTY,
                            LOGIN_FAIL_TIMEOUT_RATIO_PROPERTY}, tenantDomain);
        } catch (IdentityGovernanceException e) {
            throw Utils.handleServerException(Constants.ErrorMessage.SERVER_ERROR_RETRIEVING_ACCOUNT_LOCK_CONFIGS, null,
                    e);
        }
    }
}
