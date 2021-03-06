/*
 * Copyright (c) [2016-2017] [University of Minnesota]
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.grouplens.samantha.modeler.solver;

import org.grouplens.samantha.modeler.common.LearningData;
import org.grouplens.samantha.modeler.common.PredictiveModel;
import org.grouplens.samantha.server.exception.BadRequestException;

abstract public class AbstractOptimizationMethod implements OptimizationMethod {
    final protected double tol;
    final protected int maxIter;
    final protected int minIter;

    public AbstractOptimizationMethod(double tol, int maxIter, int minIter) {
        this.tol = tol;
        this.maxIter = maxIter;
        this.minIter = minIter;
    }

    protected double update(LearningModel model, LearningData learningData) {
        throw new BadRequestException("update method is not supported.");
    }

    public double minimize(LearningModel learningModel, LearningData learningData, LearningData validData) {
        TerminationCriterion learnCrit = new TerminationCriterion(tol, maxIter, minIter);
        TerminationCriterion validCrit = null;
        if (validData != null) {
            validCrit = new TerminationCriterion(tol, maxIter, minIter);
        }
        double learnObjVal = 0.0;
        while (learnCrit.keepIterate()) {
            if (validCrit != null && !(validCrit.keepIterate())) {
                break;
            }
            learnObjVal = this.update(learningModel, learningData);
            learnCrit.addIteration(AbstractOptimizationMethod.class.toString()
                    + " -- Learning", learnObjVal);
            if (validData != null) {
                double validObjVal = SolverUtilities.evaluate(learningModel, validData);
                validCrit.addIteration(AbstractOptimizationMethod.class.toString()
                        + " -- Validating", validObjVal);
            }
        }
        return learnObjVal;
    }

    public void learn(PredictiveModel model, LearningData learningData, LearningData validData) {
        LearningModel learningModel = (LearningModel) model;
        minimize(learningModel, learningData, validData);
    }
}
