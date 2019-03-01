package com.ctrip.framework.apollo.openapi.dto;

public class OpenItemDTO extends BaseDTO {

  private String key;

  private String value;

  private String comment;

  private boolean isRelease;

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  public boolean isRelease() {
    return isRelease;
  }

  public void setRelease(boolean release) {
    isRelease = release;
  }
}