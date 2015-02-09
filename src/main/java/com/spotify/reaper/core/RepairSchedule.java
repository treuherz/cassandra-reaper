/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spotify.reaper.core;

import org.apache.cassandra.repair.RepairParallelism;
import org.joda.time.DateTime;

public class RepairSchedule {

  private final long id;

  private final long repairUnitId;
  private final State state;
  private final int daysBetween;
  private final DateTime nextActivation;
  private final long[] runHistory;
  private final int segmentCount;
  private final RepairParallelism repairParallelism;
  private final double intensity;
  private final DateTime creationTime;
  private final String owner;
  private final DateTime pauseTime;
  private final String lastEvent;
  private final double intensity;

  private RepairSchedule(Builder builder, long id) {
    this.id = id;
    this.repairUnitId = builder.repairUnitId;
    this.state = builder.state;
    this.daysBetween = builder.daysBetween;
    this.nextActivation = builder.nextActivation;
    this.runHistory = builder.runHistory;
    this.segmentCount = builder.segmentCount;
    this.repairParallelism = builder.repairParallelism;
    this.intensity = builder.intensity;
    this.creationTime = builder.creationTime;
    this.owner = builder.owner;
    this.pauseTime = builder.pauseTime;
    this.lastEvent = builder.lastEvent;
    this.intensity = builder.intensity;
  }

  public long getId() {
    return id;
  }

  public long getRepairUnitId() {
    return repairUnitId;
  }

  public State getState() {
    return state;
  }

  public int getDaysBetween() {
    return daysBetween;
  }

  public DateTime getFollowingActivation() {
    return getNextActivation().plusDays(getDaysBetween());
  }

  public DateTime getNextActivation() {
    return nextActivation;
  }

  public long[] getRunHistory() {
    return runHistory;
  }

  public int getSegmentCount() {
    return segmentCount;
  }

  public RepairParallelism getRepairParallelism() {
    return repairParallelism;
  }

  public double getIntensity() {
    return intensity;
  }

  public DateTime getCreationTime() {
    return creationTime;
  }

  public String getOwner() {
    return owner;
  }

  public DateTime getPauseTime() {
    return pauseTime;
  }

  public String getLastEvent() {
    return lastEvent;
  }

  public double getIntensity() {
    return intensity;
  }

  public Builder with() {
    return new Builder(this);
  }

  public enum State {
    RUNNING,
    PAUSED
  }


  public static class Builder {

    public final long repairUnitId;
    private State state;
    private int daysBetween;
    private DateTime nextActivation;
    private long[] runHistory;
    private int segmentCount;
    private RepairParallelism repairParallelism;
    private double intensity;
    private DateTime creationTime;
    private String owner;
    private DateTime pauseTime;
    private String lastEvent = "no events";
    private double intensity;

    public Builder(long repairUnitId, State state, int daysBetween, DateTime nextActivation,
        long[] runHistory, int segmentCount, RepairParallelism repairParallelism, double intensity,
        DateTime creationTime) {
      this.repairUnitId = repairUnitId;
      this.state = state;
      this.daysBetween = daysBetween;
      this.nextActivation = nextActivation;
      this.runHistory = runHistory;
      this.segmentCount = segmentCount;
      this.repairParallelism = repairParallelism;
      this.intensity = intensity;
      this.creationTime = creationTime;
    }

    private Builder(RepairSchedule original) {
      repairUnitId = original.repairUnitId;
      state = original.state;
      daysBetween = original.daysBetween;
      nextActivation = original.nextActivation;
      runHistory = original.runHistory;
      segmentCount = original.segmentCount;
      repairParallelism = original.repairParallelism;
      intensity = original.intensity;
      creationTime = original.creationTime;
      owner = original.owner;
      pauseTime = original.pauseTime;
      lastEvent = original.lastEvent;
      intensity = original.intensity;
    }

    public Builder runState(State state) {
      this.state = state;
      return this;
    }

    public Builder daysBetween(int daysBetween) {
      this.daysBetween = daysBetween;
      return this;
    }

    public Builder nextActivation(DateTime nextActivation) {
      this.nextActivation = nextActivation;
      return this;
    }

    public Builder runHistory(long[] runHistory) {
      this.runHistory = runHistory;
      return this;
    }

    public Builder segmentCount(int segmentCount) {
      this.segmentCount = segmentCount;
      return this;
    }

    public Builder repairParallelism(RepairParallelism repairParallelism) {
      this.repairParallelism = repairParallelism;
      return this;
    }

    public Builder intensity(long intensity) {
      this.intensity = intensity;
      return this;
    }

    public Builder creationTime(DateTime creationTime) {
      this.creationTime = creationTime;
      return this;
    }

    public Builder owner(String owner) {
      this.owner = owner;
      return this;
    }

    public Builder pauseTime(DateTime pauseTime) {
      this.pauseTime = pauseTime;
      return this;
    }

    public Builder lastEvent(String lastEvent) {
      this.lastEvent = lastEvent;
      return this;
    }

    public Builder intensity(double intensity) {
      this.intensity = intensity;
      return this;
    }

    public RepairSchedule build(long id) {
      return new RepairSchedule(this, id);
    }
  }
}
