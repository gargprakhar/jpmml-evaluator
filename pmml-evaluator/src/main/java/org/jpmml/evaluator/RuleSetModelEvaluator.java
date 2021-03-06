/*
 * Copyright (c) 2013 Villu Ruusmann
 *
 * This file is part of JPMML-Evaluator
 *
 * JPMML-Evaluator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-Evaluator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-Evaluator.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.evaluator;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import org.dmg.pmml.CompoundRule;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.MiningFunctionType;
import org.dmg.pmml.PMML;
import org.dmg.pmml.Predicate;
import org.dmg.pmml.Rule;
import org.dmg.pmml.RuleSelectionMethod;
import org.dmg.pmml.RuleSet;
import org.dmg.pmml.RuleSetModel;
import org.dmg.pmml.SimpleRule;
import org.jpmml.manager.InvalidFeatureException;
import org.jpmml.manager.UnsupportedFeatureException;

public class RuleSetModelEvaluator extends ModelEvaluator<RuleSetModel> {

	public RuleSetModelEvaluator(PMML pmml){
		this(pmml, find(pmml.getModels(), RuleSetModel.class));
	}

	public RuleSetModelEvaluator(PMML pmml, RuleSetModel ruleSetModel){
		super(pmml, ruleSetModel);
	}

	@Override
	public String getSummary(){
		return "Ruleset model";
	}

	@Override
	public Map<FieldName, ?> evaluate(ModelEvaluationContext context){
		RuleSetModel ruleSetModel = getModel();
		if(!ruleSetModel.isScorable()){
			throw new InvalidResultException(ruleSetModel);
		}

		Map<FieldName, ? extends ClassificationMap<?>> predictions;

		MiningFunctionType miningFunction = ruleSetModel.getFunctionName();
		switch(miningFunction){
			case CLASSIFICATION:
				predictions = evaluateClassification(context);
				break;
			default:
				throw new UnsupportedFeatureException(ruleSetModel, miningFunction);
		}

		return OutputUtil.evaluate(predictions, context);
	}

	private Map<FieldName, ? extends ClassificationMap<?>> evaluateClassification(ModelEvaluationContext context){
		RuleSetModel ruleSetModel = getModel();

		RuleSet ruleSet = ruleSetModel.getRuleSet();

		List<RuleSelectionMethod> ruleSelectionMethods = ruleSet.getRuleSelectionMethods();
		if(ruleSelectionMethods.size() < 1){
			throw new InvalidFeatureException(ruleSet);
		}

		// "If more than one method is included, the first method is used as the default method for scoring"
		RuleSelectionMethod ruleSelectionMethod = ruleSelectionMethods.get(0);

		// Both the ordering of keys and values is significant
		ListMultimap<String, SimpleRule> firedRules = LinkedListMultimap.create();

		List<Rule> rules = ruleSet.getRules();
		for(Rule rule : rules){
			collectFiredRules(firedRules, rule, context);
		}

		RuleClassificationMap result = new RuleClassificationMap();

		// Return the default prediction when no rules in the ruleset fire
		if(firedRules.size() == 0){
			String score = ruleSet.getDefaultScore();

			result.put(new SimpleRule(score), score, ruleSet.getDefaultConfidence());

			return TargetUtil.evaluateClassification(result, context);
		}

		RuleSelectionMethod.Criterion criterion = ruleSelectionMethod.getCriterion();

		Set<String> keys = firedRules.keySet();
		for(String key : keys){
			List<SimpleRule> keyRules = firedRules.get(key);

			switch(criterion){
				case FIRST_HIT:
					{
						SimpleRule winner = keyRules.get(0);

						// The first value of the first key
						if(result.getEntity() == null){
							result.setEntity(winner);
						}

						result.put(key, winner.getConfidence());
					}
					break;
				case WEIGHTED_SUM:
					{
						SimpleRule winner = null;

						double totalWeight = 0;

						for(SimpleRule keyRule : keyRules){

							if(winner == null || (winner.getWeight() < keyRule.getWeight())){
								winner = keyRule;
							}

							totalWeight += keyRule.getWeight();
						}

						result.put(winner, key, totalWeight / firedRules.size());
					}
					break;
				case WEIGHTED_MAX:
					{
						SimpleRule winner = null;

						for(SimpleRule keyRule : keyRules){

							if(winner == null || (winner.getWeight() < keyRule.getWeight())){
								winner = keyRule;
							}
						}

						result.put(winner, key, winner.getConfidence());
					}
					break;
				default:
					throw new UnsupportedFeatureException(ruleSelectionMethod, criterion);
			}
		}

		return TargetUtil.evaluateClassification(result, context);
	}

	static
	private void collectFiredRules(ListMultimap<String, SimpleRule> firedRules, Rule rule, EvaluationContext context){
		Predicate predicate = rule.getPredicate();
		if(predicate == null){
			throw new InvalidFeatureException(rule);
		}

		Boolean status = PredicateUtil.evaluate(predicate, context);
		if(status == null || !status.booleanValue()){
			return;
		} // End if

		if(rule instanceof SimpleRule){
			SimpleRule simpleRule = (SimpleRule)rule;

			firedRules.put(simpleRule.getScore(), simpleRule);
		} else

		if(rule instanceof CompoundRule){
			CompoundRule compoundRule = (CompoundRule)rule;

			List<Rule> childRules = compoundRule.getRules();
			for(Rule childRule : childRules){
				collectFiredRules(firedRules, childRule, context);
			}
		} else

		{
			throw new UnsupportedFeatureException(rule);
		}
	}
}