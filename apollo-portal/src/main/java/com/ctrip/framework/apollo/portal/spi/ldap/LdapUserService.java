package com.ctrip.framework.apollo.portal.spi.ldap;

import com.ctrip.framework.apollo.portal.entity.bo.UserInfo;
import com.ctrip.framework.apollo.portal.spi.UserService;
import com.google.common.base.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.ContainerCriteria;
import org.springframework.ldap.query.SearchScope;
import org.springframework.util.CollectionUtils;

import java.util.List;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

/**
 * @author xm.lin xm.lin@anxincloud.com
 * @Description
 * @date 18-8-9 下午4:42
 */
public class LdapUserService implements UserService {

    @Value("${ldap.mapping.objectClass}")
    private String objectClassAttrName;
    @Value("${ldap.mapping.loginId}")
    private String loginIdAttrName;
    @Value("${ldap.mapping.userDisplayName}")
    private String userDisplayNameAttrName;
    @Value("${ldap.mapping.email}")
    private String emailAttrName;
    @Value("${ldap.mapping.filterBase}")
    private String filterBase;
    @Autowired
    private LdapTemplate ldapTemplate;

    private ContextMapper<UserInfo> ldapUserInfoMapper = (ctx) -> {
        DirContextAdapter contextAdapter = (DirContextAdapter) ctx;
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(contextAdapter.getStringAttribute(loginIdAttrName));
        userInfo.setName(contextAdapter.getStringAttribute(userDisplayNameAttrName));
        userInfo.setEmail(contextAdapter.getStringAttribute(emailAttrName));
        return userInfo;
    };

    private ContainerCriteria ldapQueryCriteria() {
        //忽略PartialResultException异常
        ldapTemplate.setIgnorePartialResultException(true);
        ContainerCriteria criteria = query().base(filterBase)
                .searchScope(SearchScope.SUBTREE)
                .where("objectClass").is(objectClassAttrName);
        return criteria;
    }


    @Override
    public List<UserInfo> searchUsers(String keyword, int offset, int limit) {
        ContainerCriteria criteria = ldapQueryCriteria();
        if (!Strings.isNullOrEmpty(keyword)) {
            criteria.and(query().where(loginIdAttrName).like(keyword + "*").or(userDisplayNameAttrName)
                    .like(keyword + "*"));
        }
        return ldapTemplate.search(criteria, ldapUserInfoMapper);
    }

    @Override
    public UserInfo findByUserId(String userId) {
        return ldapTemplate
                .searchForObject(ldapQueryCriteria().and(loginIdAttrName).is(userId), ldapUserInfoMapper);
    }

    @Override
    public List<UserInfo> findByUserIds(List<String> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return null;
        } else {
            ContainerCriteria criteria = ldapQueryCriteria()
                    .and(query().where(loginIdAttrName).is(userIds.get(0)));
            userIds.stream().skip(1).forEach(userId -> criteria.or(loginIdAttrName).is(userId));
            return ldapTemplate.search(criteria, ldapUserInfoMapper);
        }
    }

}