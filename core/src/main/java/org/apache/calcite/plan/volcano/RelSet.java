/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.plan.volcano;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptListener;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.Converter;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.ImmutableList;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A <code>RelSet</code> is an equivalence-set of expressions; that is, a set of
 * expressions which have identical semantics. We are generally interested in
 * using the expression which has the lowest cost.
 *
 * <p>All of the expressions in an <code>RelSet</code> have the same calling
 * convention.</p>
 */
class RelSet {
  //~ Static fields/initializers ---------------------------------------------

  private static final Logger LOGGER = CalciteTrace.getPlannerTracer();

  //~ Instance fields --------------------------------------------------------

  final List<RelNode> rels = new ArrayList<>();
  /**
   * Relational expressions that have a subset in this set as a child. This
   * is a multi-set. If multiple relational expressions in this set have the
   * same parent, there will be multiple entries.
   */
  final List<RelNode> parents = new ArrayList<>();
  final List<RelSubset> subsets = new ArrayList<>();

  /**
   * Set to the superseding set when this is found to be equivalent to another
   * set.
   */
  RelSet equivalentSet;
  RelNode rel;

  /**
   * Variables that are set by relational expressions in this set
   * and available for use by parent and child expressions.
   */
  final Set<CorrelationId> variablesPropagated;

  /**
   * Variables that are used by relational expressions in this set.
   */
  final Set<CorrelationId> variablesUsed;
  final int id;

  /**
   * Reentrancy flag.
   */
  boolean inMetadataQuery;

  //~ Constructors -----------------------------------------------------------

  RelSet(
      int id,
      Set<CorrelationId> variablesPropagated,
      Set<CorrelationId> variablesUsed) {
    this.id = id;
    this.variablesPropagated = variablesPropagated;
    this.variablesUsed = variablesUsed;
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Returns all of the {@link RelNode}s which reference {@link RelNode}s in
   * this set.
   */
  public List<RelNode> getParentRels() {
    return parents;
  }

  /**
   * Returns the child Relset for current set
   */
  public Set<RelSet> getChildSets(VolcanoPlanner planner) {
    Set<RelSet> childSets = new HashSet<>();
    for (RelNode node : this.rels) {
      if (node instanceof Converter) {
        continue;
      }
      for (RelNode child : node.getInputs()) {
        RelSet childSet = planner.equivRoot(((RelSubset) child).getSet());
        if (childSet.id != this.id) {
          childSets.add(childSet);
        }
      }
    }
    return childSets;
  }

  /**
   * @return all of the {@link RelNode}s contained by any subset of this set
   * (does not include the subset objects themselves)
   */
  public List<RelNode> getRelsFromAllSubsets() {
    return rels;
  }

  public RelSubset getSubset(RelTraitSet traits) {
    for (RelSubset subset : subsets) {
      if (subset.getTraitSet().equals(traits)) {
        return subset;
      }
    }
    return null;
  }

  /**
   * Removes all references to a specific {@link RelNode} in both the subsets
   * and their parent relationships.
   */
  void obliterateRelNode(RelNode rel) {
    parents.remove(rel);
  }

  /**
   * Adds a relational expression to a set, with its results available under a
   * particular calling convention. An expression may be in the set several
   * times with different calling conventions (and hence different costs).
   */
  public RelSubset add(RelNode rel) {
    assert equivalentSet == null : "adding to a dead set";
    final RelTraitSet traitSet = rel.getTraitSet().simplify();
    final RelSubset subset = getOrCreateSubset(
        rel.getCluster(), traitSet, rel.isEnforcer());
    subset.add(rel);
    return subset;
  }

  /**
   * If the subset is required, convert derived subsets to this subset.
   * Otherwise, convert this subset to required subsets in this RelSet.
   * The subset can be both required and derived.
   */
  private void addAbstractConverters(
      RelOptCluster cluster, RelSubset subset, boolean required) {
    List<RelSubset> others = subsets.stream().filter(
        n -> required ? n.isDerived() : n.isRequired())
        .collect(Collectors.toList());

    for (RelSubset other : others) {
      assert other.getTraitSet().size() == subset.getTraitSet().size();
      RelSubset from = subset;
      RelSubset to = other;

      if (required) {
        from = other;
        to = subset;
      }

      if (from == to || !from.getConvention()
          .useAbstractConvertersForConversion(
              from.getTraitSet(), to.getTraitSet())) {
        continue;
      }

      final ImmutableList<RelTrait> difference =
          to.getTraitSet().difference(from.getTraitSet());

      boolean needsConverter = false;

      for (RelTrait fromTrait : difference) {
        RelTraitDef traitDef = fromTrait.getTraitDef();
        RelTrait toTrait = to.getTraitSet().getTrait(traitDef);

        if (toTrait == null || !traitDef.canConvert(
            cluster.getPlanner(), fromTrait, toTrait)) {
          needsConverter = false;
          break;
        }

        if (!fromTrait.satisfies(toTrait)) {
          needsConverter = true;
        }
      }

      if (needsConverter) {
        final AbstractConverter converter =
            new AbstractConverter(cluster, from, null, to.getTraitSet());
        cluster.getPlanner().register(converter, to);
      }
    }
  }

  RelSubset getOrCreateSubset(RelOptCluster cluster, RelTraitSet traits) {
    return getOrCreateSubset(cluster, traits, false);
  }

  RelSubset getOrCreateSubset(
      RelOptCluster cluster, RelTraitSet traits, boolean required) {
    boolean needsConverter = false;
    RelSubset subset = getSubset(traits);

    if (subset == null) {
      needsConverter = true;
      subset = new RelSubset(cluster, this, traits);

      // Need to first add to subset before adding the abstract
      // converters (for others->subset), since otherwise during
      // register() the planner will try to add this subset again.
      subsets.add(subset);

      final VolcanoPlanner planner = (VolcanoPlanner) cluster.getPlanner();
      if (planner.listener != null) {
        postEquivalenceEvent(planner, subset);
      }
    } else if ((required && !subset.isRequired())
        || (!required && !subset.isDerived())) {
      needsConverter = true;
    }

    if (subset.getConvention() == Convention.NONE) {
      needsConverter = false;
    } else if (required) {
      subset.setRequired();
    } else {
      subset.setDerived();
    }

    if (needsConverter) {
      addAbstractConverters(cluster, subset, required);
    }

    return subset;
  }

  private void postEquivalenceEvent(VolcanoPlanner planner, RelNode rel) {
    RelOptListener.RelEquivalenceEvent event =
        new RelOptListener.RelEquivalenceEvent(
            planner,
            rel,
            "equivalence class " + id,
            false);
    planner.listener.relEquivalenceFound(event);
  }

  /**
   * Adds an expression <code>rel</code> to this set, without creating a
   * {@link org.apache.calcite.plan.volcano.RelSubset}. (Called only from
   * {@link org.apache.calcite.plan.volcano.RelSubset#add}.
   *
   * @param rel Relational expression
   */
  void addInternal(RelNode rel) {
    if (!rels.contains(rel)) {
      rels.add(rel);
      for (RelTrait trait : rel.getTraitSet()) {
        assert trait == trait.getTraitDef().canonize(trait);
      }

      VolcanoPlanner planner =
          (VolcanoPlanner) rel.getCluster().getPlanner();
      if (planner.listener != null) {
        postEquivalenceEvent(planner, rel);
      }
    }
    if (this.rel == null) {
      this.rel = rel;
    } else {
      // Row types must be the same, except for field names.
      RelOptUtil.verifyTypeEquivalence(
          this.rel,
          rel,
          this);
    }
  }

  /**
   * Merges <code>otherSet</code> into this RelSet.
   *
   * <p>One generally calls this method after discovering that two relational
   * expressions are equivalent, and hence the <code>RelSet</code>s they
   * belong to are equivalent also.
   *
   * <p>After this method completes, <code>otherSet</code> is obsolete, its
   * {@link #equivalentSet} member points to this RelSet, and this RelSet is
   * still alive.
   *
   * @param planner  Planner
   * @param otherSet RelSet which is equivalent to this one
   */
  void mergeWith(
      VolcanoPlanner planner,
      RelSet otherSet) {
    assert this != otherSet;
    assert this.equivalentSet == null;
    assert otherSet.equivalentSet == null;
    LOGGER.trace("Merge set#{} into set#{}", otherSet.id, id);
    otherSet.equivalentSet = this;
    RelOptCluster cluster = rel.getCluster();
    RelMetadataQuery mq = cluster.getMetadataQuery();

    // remove from table
    boolean existed = planner.allSets.remove(otherSet);
    assert existed : "merging with a dead otherSet";

    Map<RelSubset, RelNode> changedSubsets = new IdentityHashMap<>();

    // merge subsets
    for (RelSubset otherSubset : otherSet.subsets) {
      RelSubset subset = null;
      RelTraitSet otherTraits = otherSubset.getTraitSet();

      // If it is logical or derived physical traitSet
      if (otherSubset.isDerived() || !otherSubset.isRequired()) {
        subset = getOrCreateSubset(cluster, otherTraits, false);
      }

      // It may be required only, or both derived and required,
      // in which case, register again.
      if (otherSubset.isRequired()) {
        subset = getOrCreateSubset(cluster, otherTraits, true);
      }

      // collect RelSubset instances, whose best should be changed
      if (otherSubset.bestCost.isLt(subset.bestCost)) {
        changedSubsets.put(subset, otherSubset.best);
      }
    }

    Set<RelNode> parentRels = new HashSet<>(parents);
    for (RelNode otherRel : otherSet.rels) {
      if (planner.prunedNodes.contains(otherRel)) {
        continue;
      }

      boolean pruned = false;
      if (parentRels.contains(otherRel)) {
        // if otherRel is a enforcing operator e.g.
        // Sort, Exchange, do not prune it.
        if (otherRel.getInputs().size() != 1
            || otherRel.getInput(0).getTraitSet()
                .satisfies(otherRel.getTraitSet())) {
          pruned = true;
        }
      }

      if (pruned) {
        planner.prune(otherRel);
      } else {
        planner.reregister(this, otherRel);
      }
    }

    // Has another set merged with this?
    assert equivalentSet == null;

    // calls propagateCostImprovements() for RelSubset instances,
    // whose best should be changed to check whether that
    // subset's parents get cheaper.
    Set<RelSubset> activeSet = new HashSet<>();
    for (Map.Entry<RelSubset, RelNode> subsetBestPair : changedSubsets.entrySet()) {
      RelSubset relSubset = subsetBestPair.getKey();
      relSubset.propagateCostImprovements(
          planner, mq, subsetBestPair.getValue(),
          activeSet);
    }
    assert activeSet.isEmpty();

    // Update all rels which have a child in the other set, to reflect the
    // fact that the child has been renamed.
    //
    // Copy array to prevent ConcurrentModificationException.
    final List<RelNode> previousParents =
        ImmutableList.copyOf(otherSet.getParentRels());
    for (RelNode parentRel : previousParents) {
      planner.rename(parentRel);
    }

    // Renaming may have caused this set to merge with another. If so,
    // this set is now obsolete. There's no need to update the children
    // of this set - indeed, it could be dangerous.
    if (equivalentSet != null) {
      return;
    }

    // Make sure the cost changes as a result of merging are propagated.
    for (RelNode parentRel : getParentRels()) {
      final RelSubset parentSubset = planner.getSubset(parentRel);
      parentSubset.propagateCostImprovements(
          planner, mq, parentRel,
          activeSet);
    }
    assert activeSet.isEmpty();
    assert equivalentSet == null;

    // Each of the relations in the old set now has new parents, so
    // potentially new rules can fire. Check for rule matches, just as if
    // it were newly registered.  (This may cause rules which have fired
    // once to fire again.)
    for (RelNode rel : rels) {
      assert planner.getSet(rel) == this;
      planner.fireRules(rel);
    }
    // Fire rule match on subsets as well
    for (RelSubset subset : subsets) {
      if (subset.isDerived()) {
        planner.fireRules(subset);
      }
    }
  }
}
