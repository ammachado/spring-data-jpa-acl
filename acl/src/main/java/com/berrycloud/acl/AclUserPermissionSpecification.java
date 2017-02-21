package com.berrycloud.acl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Selection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.GrantedAuthority;

import com.berrycloud.acl.annotation.AclOwner;
import com.berrycloud.acl.annotation.AclParent;
import com.berrycloud.acl.data.AclEntityMetaData;
import com.berrycloud.acl.data.AclMetaData;
import com.berrycloud.acl.data.OwnerData;
import com.berrycloud.acl.domain.AclEntity;
import com.berrycloud.acl.domain.AclUser;
import com.berrycloud.acl.security.AclUserDetails;
import com.berrycloud.acl.security.AclUserDetailsService;

public class AclUserPermissionSpecification implements Specification<Object> {

  private static Logger LOG = LoggerFactory.getLogger(AclUserPermissionSpecification.class);

  @Autowired
  private AclUserDetailsService<? extends GrantedAuthority> aclUserDetailsService;

  @Autowired
  private AclMetaData aclMetaData;

  @SuppressWarnings("unchecked")
  @Override
  public Predicate toPredicate(Root<Object> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
    
    // Gather the id of current user
    AclUserDetails user = aclUserDetailsService.getCurrentUser();
    if(user== null) {
      LOG.trace("Access denied for NULL user");
      return cb.disjunction();
    }

    // Skip all predicate constructions if the current user is an admin
    if (aclUserDetailsService.isAdmin()) {
      LOG.trace("Access granted for ADMIN user: {}",user.getUsername());
      return cb.conjunction();
    }
    
    if(!AclEntity.class.isAssignableFrom(root.getJavaType())) {
      LOG.trace("Access granted for non-AclEntity: {}",root.getJavaType());
      return cb.conjunction();
    }
    
    From<Object, Object> from = root;
    Selection<?> selection=query.getSelection();
    if(selection!=null && selection instanceof From) {
      from = (From<Object, Object>) selection;
    }
    LOG.trace("Creating predicates for {}", from.getJavaType());
    // LOG.trace("ResultType: {}",query.getResultType());
    // LOG.trace("Root joins: {}",root.getJoins());
    LOG.trace("Query Selection: {}",query.getSelection());
    LOG.trace("Query Root: {}",root);
    
    // Must be set to distinct result, because of cross-joins (no more cross-joins !!!)
    //query.distinct(true);

    // TODO check id sorting
    List<Order> orderList = new ArrayList<>(query.getOrderList());
    if (orderList.isEmpty()) {
      // Need ordering for pagination
      orderList.add(cb.asc(from.get("id")));
      query.orderBy(orderList);
    }

    // TODO: check for multiple roots and check permissions for ALL roots using AND
    // Currently there is no use-case for it, but who knows...
    return toSubPredicate(from, query, cb, user.getUserId());
  }

  protected Predicate toSubPredicate(From<Object, Object> from, CriteriaQuery<?> query, CriteriaBuilder cb,
      Serializable userId) {

    List<Predicate> predicates = new ArrayList<>();

    predicates.addAll(createSelfPredicates(from, query, cb, userId));
    predicates.addAll(createOwnerPredicates(from, query, cb, userId));
    predicates.addAll(createPermissionPredicates(from, query, cb, userId));
    // Adding predicates recursively for parent entities
    predicates.addAll(createParentPredicates(from, query, cb, userId));

    if(predicates.isEmpty()) {
      System.out.println("EMPTY");
      //return cb.conjunction();
      //predicates.add(cb.equal(from.get("id"), from.get("id")));
    }
    return cb.or(predicates.toArray(new Predicate[predicates.size()]));
  }

  /**
   * Creates predicates for current user
   */
  private List<Predicate> createSelfPredicates(From<Object, Object> from, CriteriaQuery<?> query, CriteriaBuilder cb,
      Serializable userId) {
    List<Predicate> predicates = new ArrayList<>();
    if (AclUser.class.isAssignableFrom(from.getJavaType())) {
      LOG.trace("Adding 'self' predicate for {}", from.getJavaType());
      //from.join("id", JoinType.LEFT).on(cb.equal(from.get("id"), userId));
      predicates.add(cb.equal(from.get("id"), userId));
    }
    return predicates;
  }

  /**
   * Creates predicates for direct owners defined by {@link AclOwner} annotation
   */
  private List<Predicate> createOwnerPredicates(From<Object, Object> from, CriteriaQuery<?> query, CriteriaBuilder cb,
      Serializable userId) {
    List<Predicate> predicates = new ArrayList<>();
    AclEntityMetaData metaData = aclMetaData.getAclEntityMetaData(from.getJavaType());
    for (OwnerData ownerData : metaData.getOwnerDataList()) {
      LOG.trace("Adding 'owner' predicate for {}.{}", from.getJavaType(), ownerData.getAttributeName());
      //Join join = from.join(ownerData.getAttributeName(), JoinType.LEFT);
      //join.on(cb.equal(join.get("id"), userId));
      predicates.add(cb.equal(from.get(ownerData.getAttributeName()).get("id"), userId));
    }
    return predicates;
  }

  /**
   * Creates predicates for parent objects defined by {@link AclParent} annotation
   * 
   */
  private List<Predicate> createParentPredicates(From<Object, Object> from, CriteriaQuery<?> query, CriteriaBuilder cb,
      Serializable userId) {
    List<Predicate> predicates = new ArrayList<>();
    AclEntityMetaData metaData = aclMetaData.getAclEntityMetaData(from.getJavaType());
    for (String parent : metaData.getParentList()) {
      LOG.trace("Adding 'parent' sub-predicates for {}.{}", from.getJavaType(), parent);
      predicates.add(toSubPredicate(from.join(parent, JoinType.LEFT), query, cb, userId));
    }
    return predicates;
  }

  /**
   * Creates predicates for permissionLinks
   */
  private List<Predicate> createPermissionPredicates(From<Object, Object> from, CriteriaQuery<?> query, CriteriaBuilder cb,
      Serializable userId) {
    List<Predicate> predicates = new ArrayList<>();

    AclEntityMetaData metaData = aclMetaData.getAclEntityMetaData(from.getJavaType());
    for (String permissionLinkOwner : metaData.getPermissionLinkOwnerList()) {
      LOG.trace("Adding 'permission-link' predicate for {}.{}", from.getJavaType(), permissionLinkOwner);
      Join<?,?> permissionLink = from.join(permissionLinkOwner, JoinType.LEFT);
      predicates.add(cb.equal(permissionLink.get("owner").get("id"), userId));
    }

    return predicates;
  }

}
