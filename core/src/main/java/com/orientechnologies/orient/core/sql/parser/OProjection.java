/* Generated By:JJTree: Do not edit this line. OProjection.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.record.OElement;
import com.orientechnologies.orient.core.sql.OCommandSQLParsingException;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultInternal;
import com.orientechnologies.orient.core.sql.query.OLegacyResultSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class OProjection extends SimpleNode {

  protected boolean distinct = false;

  List<OProjectionItem> items;

  public OProjection(int id) {
    super(id);
  }

  public OProjection(OrientSql p, int id) {
    super(p, id);
  }

  /**
   * Accept the visitor.
   **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  public List<OProjectionItem> getItems() {
    return items;
  }

  public void setItems(List<OProjectionItem> items) {
    this.items = items;
  }

  @Override
  public void toString(Map<Object, Object> params, StringBuilder builder) {
    if (items == null) {
      return;
    }
    boolean first = true;

    if (distinct) {
      builder.append("DISTINCT ");
    }
    // print * before
    for (OProjectionItem item : items) {
      if (item.isAll()) {
        if (!first) {
          builder.append(", ");
        }

        item.toString(params, builder);
        first = false;
      }
    }

    // and then the rest of the projections
    for (OProjectionItem item : items) {
      if (!item.isAll()) {
        if (!first) {
          builder.append(", ");
        }

        item.toString(params, builder);
        first = false;
      }
    }
  }

  public OResult calculateSingle(OCommandContext iContext, OResult iRecord) {
    if (isExpand()) {
      throw new IllegalStateException("This is an expand projection, it cannot be calculated as a single result" + toString());
    }

    if (items.size() == 0 || (items.size() == 1 && items.get(0).isAll()) && items.get(0).nestedProjection == null) {
      return iRecord;
    }

    OResultInternal result = new OResultInternal();
    for (OProjectionItem item : items) {
      if (item.isAll()) {
        for (String alias : iRecord.getPropertyNames()) {
          result.setProperty(alias, iRecord.getProperty(alias));
        }
        if (iRecord.getElement().isPresent()) {
          OElement x = iRecord.getElement().get();
          result.setProperty("@rid", x.getIdentity());
          result.setProperty("@version", x.getVersion());
//          result.setProperty("@class", x.getSchemaType().orElse(null));
        }
      } else {
        result.setProperty(item.getProjectionAliasAsString(), item.execute(iRecord, iContext));
      } if (item.nestedProjection != null) {
        result = (OResultInternal) item.nestedProjection.apply(item.expression, result, iContext);
      }
    } return result;
  }

  public OLegacyResultSet calculateExpand(OCommandContext iContext, OResult iRecord) {
    if (!isExpand()) {
      throw new IllegalStateException("This is not an expand projection:" + toString());
    }
    throw new UnsupportedOperationException("Implement expand in projection");
  }

  public boolean isExpand() {
    return items != null && items.size() == 1 && items.get(0).isExpand();
  }

  public void validate() {
    if (items != null && items.size() > 1) {
      for (OProjectionItem item : items) {
        if (item.isExpand()) {
          throw new OCommandSQLParsingException("Cannot execute a query with expand() together with other projections");
        }
      }
    }
  }

  public OProjection getExpandContent() {
    OProjection result = new OProjection(-1);
    result.setItems(new ArrayList<>());
    result.getItems().add(this.getItems().get(0).getExpandContent());
    return result;
  }

  public Set<String> getAllAliases() {
    return items.stream().map(i -> i.getProjectionAliasAsString()).collect(Collectors.toSet());
  }

  public OProjection copy() {
    OProjection result = new OProjection(-1);
    if (items != null) {
      result.items = items.stream().map(x -> x.copy()).collect(Collectors.toList());
    }
    result.distinct = distinct;
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    OProjection that = (OProjection) o;

    if (items != null ? !items.equals(that.items) : that.items != null)
      return false;

    return true;
  }

  @Override
  public int hashCode() {
    return items != null ? items.hashCode() : 0;
  }

  public boolean isDistinct() {
    return distinct;
  }

  public void setDistinct(boolean distinct) {
    this.distinct = distinct;
  }

  public void extractSubQueries(SubQueryCollector collector) {
    if (items != null) {
      for (OProjectionItem item : items) {
        item.extractSubQueries(collector);
      }
    }
  }

  public boolean refersToParent() {
    for (OProjectionItem item : items) {
      if (item.refersToParent()) {
        return true;
      }
    }
    return false;
  }
}
/* JavaCC - OriginalChecksum=3a650307b53bae626dc063c4b35e62c3 (do not edit this line) */
