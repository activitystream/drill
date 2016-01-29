/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.planner.sql;

import io.netty.buffer.DrillBuf;

import org.apache.calcite.util.BitSets;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos;
import org.apache.drill.exec.physical.base.GroupScan;
import org.apache.drill.exec.planner.AbstractPartitionDescriptor;
import org.apache.drill.exec.planner.PartitionDescriptor;
import org.apache.drill.exec.planner.PartitionLocation;
import org.apache.drill.exec.planner.logical.DrillScanRel;
import org.apache.drill.exec.planner.physical.PlannerSettings;
import org.apache.drill.exec.store.hive.HiveUtilities;
import org.apache.drill.exec.store.hive.HiveReadEntry;
import org.apache.drill.exec.store.hive.HiveScan;
import org.apache.drill.exec.store.hive.HiveTable;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.hadoop.hive.metastore.api.Partition;

import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;

import com.google.common.collect.Lists;

// Partition descriptor for hive tables
public class HivePartitionDescriptor extends AbstractPartitionDescriptor {

  private final Map<String, Integer> partitionMap = new HashMap<>();
  private final int numPartitionLevels;
  private final DrillScanRel scanRel;
  private final String defaultPartitionValue;
  private final DrillBuf managedBuffer;

  public HivePartitionDescriptor(@SuppressWarnings("unused") final PlannerSettings settings, final DrillScanRel scanRel,
      final DrillBuf managedBuffer, final String defaultPartitionValue) {
    int i = 0;
    this.scanRel = scanRel;
    this.managedBuffer = managedBuffer.reallocIfNeeded(256);
    this.defaultPartitionValue = defaultPartitionValue;
    for (HiveTable.FieldSchemaWrapper wrapper : ((HiveScan) scanRel.getGroupScan()).hiveReadEntry.table.partitionKeys) {
      partitionMap.put(wrapper.name, i);
      i++;
    }
    numPartitionLevels = i;
  }

  @Override
  public int getPartitionHierarchyIndex(String partitionName) {
    return partitionMap.get(partitionName);
  }

  @Override
  public boolean isPartitionName(String name) {
    return (partitionMap.get(name) != null);
  }

  @Override
  public int getMaxHierarchyLevel() {
    return numPartitionLevels;
  }

  public String getBaseTableLocation() {
    HiveReadEntry origEntry = ((HiveScan) scanRel.getGroupScan()).hiveReadEntry;
    return origEntry.table.getTable().getSd().getLocation();
  }

  @Override
  public GroupScan createNewGroupScan(List<String> newFiles) throws ExecutionSetupException {
    HiveScan hiveScan = (HiveScan) scanRel.getGroupScan();
    HiveReadEntry origReadEntry = hiveScan.hiveReadEntry;
    List<HiveTable.HivePartition> oldPartitions = origReadEntry.partitions;
    List<HiveTable.HivePartition> newPartitions = new LinkedList<>();

    for (HiveTable.HivePartition part: oldPartitions) {
      String partitionLocation = part.getPartition().getSd().getLocation();
      for (String newPartitionLocation: newFiles) {
        if (partitionLocation.equals(newPartitionLocation)) {
          newPartitions.add(part);
        }
      }
    }

    HiveReadEntry newReadEntry = new HiveReadEntry(origReadEntry.table, newPartitions);

    return hiveScan.clone(newReadEntry);
  }

  @Override
  public void populatePartitionVectors(ValueVector[] vectors, List<PartitionLocation> partitions,
                                       BitSet partitionColumnBitSet, Map<Integer, String> fieldNameMap) {
    int record = 0;
    final HiveScan hiveScan = (HiveScan) scanRel.getGroupScan();
    final Map<String, String> partitionNameTypeMap = hiveScan.hiveReadEntry.table.getPartitionNameTypeMap();
    for(PartitionLocation partitionLocation: partitions) {
      for(int partitionColumnIndex : BitSets.toIter(partitionColumnBitSet)){
        final String hiveType = partitionNameTypeMap.get(fieldNameMap.get(partitionColumnIndex));

        final Object value = HiveUtilities.convertPartitionType(
            TypeInfoUtils.getTypeInfoFromTypeString(hiveType),
            partitionLocation.getPartitionValue(partitionColumnIndex),
            defaultPartitionValue);

        if (value != null) {
          HiveUtilities.populateVector(vectors[partitionColumnIndex], managedBuffer, value, record, record + 1);
        }
      }
      record++;
    }

    for(ValueVector v : vectors) {
      if(v == null){
        continue;
      }
      v.getMutator().setValueCount(partitions.size());
    }
  }

  @Override
  public TypeProtos.MajorType getVectorType(SchemaPath column, PlannerSettings plannerSettings) {
    HiveScan hiveScan = (HiveScan) scanRel.getGroupScan();
    String partitionName = column.getAsNamePart().getName();
    Map<String, String> partitionNameTypeMap = hiveScan.hiveReadEntry.table.getPartitionNameTypeMap();
    String hiveType = partitionNameTypeMap.get(partitionName);
    PrimitiveTypeInfo primitiveTypeInfo = (PrimitiveTypeInfo) TypeInfoUtils.getTypeInfoFromTypeString(hiveType);

    TypeProtos.MinorType partitionType = HiveUtilities.getMinorTypeFromHivePrimitiveTypeInfo(primitiveTypeInfo,
        plannerSettings.getOptions());
    return TypeProtos.MajorType.newBuilder().setMode(TypeProtos.DataMode.OPTIONAL).setMinorType(partitionType).build();
  }

  @Override
  public Integer getIdIfValid(String name) {
    return partitionMap.get(name);
  }

  @Override
  protected void createPartitionSublists() {
    List<PartitionLocation> locations = new LinkedList<>();
    HiveReadEntry origEntry = ((HiveScan) scanRel.getGroupScan()).hiveReadEntry;
    for (Partition partition: origEntry.getPartitions()) {
      locations.add(new HivePartitionLocation(partition.getValues(), partition.getSd().getLocation()));
    }
    locationSuperList = Lists.partition(locations, PartitionDescriptor.PARTITION_BATCH_SIZE);
    sublistsCreated = true;
  }

}
