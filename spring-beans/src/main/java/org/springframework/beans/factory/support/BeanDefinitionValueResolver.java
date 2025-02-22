/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.NamedBeanHolder;
import org.springframework.beans.factory.config.RuntimeBeanNameReference;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Helper class for use in bean factory implementations,
 * resolving values contained in bean definition objects
 * into the actual values applied to the target bean instance.
 *
 * <p>Operates on an {@link AbstractBeanFactory} and a plain
 * {@link org.springframework.beans.factory.config.BeanDefinition} object.
 * Used by {@link AbstractAutowireCapableBeanFactory}.
 *
 * @author Juergen Hoeller
 * @since 1.2
 * @see AbstractAutowireCapableBeanFactory
 */
class BeanDefinitionValueResolver {

	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final String beanName;

	private final BeanDefinition beanDefinition;

	private final TypeConverter typeConverter;


	/**
	 * Create a BeanDefinitionValueResolver for the given BeanFactory and BeanDefinition.
	 * @param beanFactory the BeanFactory to resolve against
	 * @param beanName the name of the bean that we work on
	 * @param beanDefinition the BeanDefinition of the bean that we work on
	 * @param typeConverter the TypeConverter to use for resolving TypedStringValues
	 */
	public BeanDefinitionValueResolver(AbstractAutowireCapableBeanFactory beanFactory, String beanName,
			BeanDefinition beanDefinition, TypeConverter typeConverter) {

		this.beanFactory = beanFactory;
		this.beanName = beanName;
		this.beanDefinition = beanDefinition;
		this.typeConverter = typeConverter;
	}


	/**
	 * Given a PropertyValue, return a value, resolving any references to other
	 * beans in the factory if necessary. The value could be:
	 * <li>A BeanDefinition, which leads to the creation of a corresponding
	 * new bean instance. Singleton flags and names of such "inner beans"
	 * are always ignored: Inner beans are anonymous prototypes.
	 * <li>A RuntimeBeanReference, which must be resolved.
	 * <li>A ManagedList. This is a special collection that may contain
	 * RuntimeBeanReferences or Collections that will need to be resolved.
	 * <li>A ManagedSet. May also contain RuntimeBeanReferences or
	 * Collections that will need to be resolved.
	 * <li>A ManagedMap. In this case the value may be a RuntimeBeanReference
	 * or Collection that will need to be resolved.
	 * <li>An ordinary object or {@code null}, in which case it's left alone.
	 * @param argName the name of the argument that the value is defined for
	 * @param value the value object to resolve
	 * @return the resolved object
	 */
	@Nullable
	public Object resolveValueIfNecessary(Object argName, @Nullable Object value) {
		// We must check each value to see whether it requires a runtime reference
		// to another bean to be resolved.
		// 我们必须检查每一个值，以查看它是否需要对另一个bean的运行时引用才能解决
		// RuntimeBeanReference：当属性值对象是工厂中另一个bean的引用时，使用不可变的占位符类，在运行时进行解析
		// 如果values是RuntimeBeanReference类型，那么就是引用类型，需要解析
		if (value instanceof RuntimeBeanReference) {
			// 将value强转为RuntimeBeanReference类型
			RuntimeBeanReference ref = (RuntimeBeanReference) value;
			// 解析出对应ref所封装的bean元信息(即Bean名，Bean类型)的Bean对象
			return resolveReference(argName, ref);
		}
		// RuntimeBeanNameReference对应于<idref bean="bea" />
		// idref注入的是目标bean的id而不是对应bean的实例，同时使用idref容器在部署的时候还会验证这个名称的bean
		// 是否真实存在，其实idref就跟value一样，只是将某个字符串注入到属性或者构造函数中，只不过注入的是某个
		// Bean实例的id属性：
		// 即<idref bean="bea" />等价于<value>bea</value>
		// 如果values是RuntimeBeanNameReference实例
		else if (value instanceof RuntimeBeanNameReference) {
			// 从value中获取引用的Bean名称
			String refName = ((RuntimeBeanNameReference) value).getBeanName();
			// 对refName进行解析，然后重新赋值给refName
			refName = String.valueOf(doEvaluate(refName));
			// 如果该工厂bean不包含具有refName的beanDefinition或外部注册的singleton实例
			if (!this.beanFactory.containsBean(refName)) {
				// 抛出BeanDefinitionStoreException存储异常：argName的Bean引用中的Bean名‘refName’无效
				throw new BeanDefinitionStoreException(
						"Invalid bean name '" + refName + "' in bean reference for " + argName);
			}
			// 返回经过解析且经过检查其是否存在于Bean工厂的引用Bean名[refName]
			return refName;
		}
		// BeanDefinitionHolder:具有名称和别名的bean定义的持有者，可以注册为内部bean占位符
		// 如果values是BeanDefinitionHolder实例
		else if (value instanceof BeanDefinitionHolder) {
			// Resolve BeanDefinitionHolder: contains BeanDefinition with name and aliases.
			// 解析BeanDefinitionHolder：包含具有名称和别名的BeanDefinition。
			// 将value强转为BeanDefinitionHolder类型
			BeanDefinitionHolder bdHolder = (BeanDefinitionHolder) value;
			// 根据bdHolder所封装的Bean名和BeanDefinition对象解析出内部Bean对象
			return resolveInnerBean(argName, bdHolder.getBeanName(), bdHolder.getBeanDefinition());
		}
		// 一般在内部匿名Bean的配置才会出现BeanDefinition
		// 如果values是BeanDefinition实例
		else if (value instanceof BeanDefinition) {
			// Resolve plain BeanDefinition, without contained name: use dummy name.
			// 解析纯BeanDefinition，不包含名称：使用虚拟名称。
			// 将value强转为BeanDefinition类型
			BeanDefinition bd = (BeanDefinition) value;
			// 拼装内部的Bean名: (inner bean)# + BeanDefinition的哈希码
			String innerBeanName = "(inner bean)" + BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR +
					ObjectUtils.getIdentityHexString(bd);
			// 根据innerBeanName和BeanDefinition对象解析出内部Bean对象
			return resolveInnerBean(argName, innerBeanName, bd);
		}
		// 如果values是DependencyDescriptor实例
		else if (value instanceof DependencyDescriptor) {
			// 定义一个用于存放所找到的所有候选Bean名称的集合，初始化长度为4
			Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
			// 根据descriptor的依赖类型解析出与descriptor所包装的对象匹配的候选Bean对象
			Object result = this.beanFactory.resolveDependency(
					(DependencyDescriptor) value, this.beanName, autowiredBeanNames, this.typeConverter);
			// 遍历autowiredBeanNames集合
			for (String autowiredBeanName : autowiredBeanNames) {
				// 如果该bean工厂包含具有autowiredBeanName的beanDefinition或外部注册的singleton实例
				if (this.beanFactory.containsBean(autowiredBeanName)) {
					// 注册autowiredBeanName为与beanName的依赖关系
					this.beanFactory.registerDependentBean(autowiredBeanName, this.beanName);
				}
			}
			// 返回与descriptor所包装的对象匹配的候选Bean对象
			return result;
		}
		// 如果values是ManagedArray实例
		else if (value instanceof ManagedArray) {
			// May need to resolve contained runtime references.
			// 可能需要包含的运行时引用，将value强转为ManagedArray类型
			ManagedArray array = (ManagedArray) value;
			// 获取array的已解析的元素类型
			Class<?> elementType = array.resolvedElementType;
			// 如果elementType为null
			if (elementType == null) {
				// 获取array的元素类型名，指array标签的value-type属性
				String elementTypeName = array.getElementTypeName();
				// 如果elementTypeName不为空
				if (StringUtils.hasText(elementTypeName)) {
					try {
						// 使用Bean工厂的Bean类型加载器加载elementTypeName对应的Class对象
						elementType = ClassUtils.forName(elementTypeName, this.beanFactory.getBeanClassLoader());
						// 让array#resolvedElementType指向elementType
						array.resolvedElementType = elementType;
					}
					// 捕捉加载elementTypeName对应的Class对象的所有异常
					catch (Throwable ex) {
						// Improve the message by showing the context.
						throw new BeanCreationException(
								this.beanDefinition.getResourceDescription(), this.beanName,
								"Error resolving array type for " + argName, ex);
					}
				}
				else {
					// 让elementType默认使用Object类型
					elementType = Object.class;
				}
			}
			// 解析managedArray对象，以得到解析后的数组对象
			return resolveManagedArray(argName, (List<?>) value, elementType);
		}
		// 对ManagedList进行解析
		else if (value instanceof ManagedList) {
			// May need to resolve contained runtime references.
			// 可能需要包含的运行时引用，解析ManagedList对象，以得到解析后的List对象并将结果返回
			return resolveManagedList(argName, (List<?>) value);
		}
		// 对ManagedSet进行解析
		else if (value instanceof ManagedSet) {
			// May need to resolve contained runtime references.
			// 可能需要包含的运行时引用，解析ManagedSet对象，以得到解析后的Set对象并将结果返回
			return resolveManagedSet(argName, (Set<?>) value);
		}
		// 对ManagedMap进行解析
		else if (value instanceof ManagedMap) {
			// May need to resolve contained runtime references.
			// 可能需要包含的运行时引用，解析ManagedMap对象，以得到解析后的Map对象并将结果返回
			return resolveManagedMap(argName, (Map<?, ?>) value);
		}
		// 对ManagedProperties进行解析
		else if (value instanceof ManagedProperties) {
			// 将value强转为ManagedProperties类型
			Properties original = (Properties) value;
			// 定义一个用于存储将original中的key和value都解析后的Properties对象
			Properties copy = new Properties();
			// 遍历original中的每一个key和value
			original.forEach((propKey, propValue) -> {
				// 如果propKey是TypedStringValue实例
				if (propKey instanceof TypedStringValue) {
					// 在propKey封装的value可解析表达式情况下，将propKey封装的value评估为表达式并解析出表达式的值
					propKey = evaluate((TypedStringValue) propKey);
				}
				// 如果propKey是TypedStringValue实例
				if (propValue instanceof TypedStringValue) {
					// 在propValue封装的value可解析表达式情况下，将propValue封装的value评估为表达式并解析出表达式的值
					propValue = evaluate((TypedStringValue) propValue);
				}
				// 如果propKey或propValue为null
				if (propKey == null || propValue == null) {
					// 抛出Bean创建异常：转换argName的属性键/值出错：解析为null
					throw new BeanCreationException(
							this.beanDefinition.getResourceDescription(), this.beanName,
							"Error converting Properties key/value pair for " + argName + ": resolved to null");
				}
				// 将propKey和propValue添加到copy中
				copy.put(propKey, propValue);
			});
			return copy;
		}
		// 对TypedStringValue进行解析
		else if (value instanceof TypedStringValue) {
			// Convert value to target type here.
			// 在此处将value转换为目标类型，将value强转为TypedStringValue类型
			TypedStringValue typedStringValue = (TypedStringValue) value;
			// 在typedStringValue封装的value可解析表达式情况下，将typedStringValue封装的value评估为表达式并解析出表达式的值
			Object valueObject = evaluate(typedStringValue);
			try {
				// 在typedStringValue中解析目标类型
				Class<?> resolvedTargetType = resolveTargetType(typedStringValue);
				// 如果resolvedTargetType不为null
				if (resolvedTargetType != null) {
					// 使用typeConverter将valueObject转换为resolvedTargetType类型
					return this.typeConverter.convertIfNecessary(valueObject, resolvedTargetType);
				}
				else {
					// 返回并解析出表达式的值
					return valueObject;
				}
			}
			// 捕捉在解析目标或转换类型过程中抛出的异常
			catch (Throwable ex) {
				// Improve the message by showing the context.
				throw new BeanCreationException(
						this.beanDefinition.getResourceDescription(), this.beanName,
						"Error converting typed String value for " + argName, ex);
			}
		}
		// 如果values是NullBean实例
		else if (value instanceof NullBean) {
			// 直接返回null
			return null;
		}
		else {
			// 对于value是String/String[]类型会尝试评估为表达式并解析出表达式的值，其他类型直接返回value
			return evaluate(value);
		}
	}

	/**
	 * Evaluate the given value as an expression, if necessary.
	 * @param value the candidate value (may be an expression)
	 * @return the resolved value
	 */
	@Nullable
	protected Object evaluate(TypedStringValue value) {
		// 如果有必要(value封装的value可解析表达式)，则将value封装的value评估为表达式并解析出表达式的值
		Object result = doEvaluate(value.getValue());
		// 如果result与value封装的value不相等
		if (!ObjectUtils.nullSafeEquals(result, value.getValue())) {
			// 将value标记为动态，即包含一个表达式，因此不进行缓存
			value.setDynamic();
		}
		return result;
	}

	/**
	 * Evaluate the given value as an expression, if necessary.
	 * @param value the original value (may be an expression)
	 * @return the resolved value if necessary, or the original value
	 */
	@Nullable
	protected Object evaluate(@Nullable Object value) {
		if (value instanceof String) {
			return doEvaluate((String) value);
		}
		else if (value instanceof String[]) {
			String[] values = (String[]) value;
			boolean actuallyResolved = false;
			Object[] resolvedValues = new Object[values.length];
			for (int i = 0; i < values.length; i++) {
				String originalValue = values[i];
				Object resolvedValue = doEvaluate(originalValue);
				if (resolvedValue != originalValue) {
					actuallyResolved = true;
				}
				resolvedValues[i] = resolvedValue;
			}
			return (actuallyResolved ? resolvedValues : values);
		}
		else {
			return value;
		}
	}

	/**
	 * Evaluate the given String value as an expression, if necessary.
	 * @param value the original value (may be an expression)
	 * @return the resolved value if necessary, or the original String value
	 */
	@Nullable
	private Object doEvaluate(@Nullable String value) {
	 	// 评估value，如果value是可解析表达式，则解析出表达式的值，否则直接返回value
		return this.beanFactory.evaluateBeanDefinitionString(value, this.beanDefinition);
	}

	/**
	 * 在给定的TypedStringValue中解析目标类型
	 * Resolve the target type in the given TypedStringValue.
	 * @param value the TypedStringValue to resolve
	 * @return the resolved target type (or {@code null} if none specified)
	 * @throws ClassNotFoundException if the specified type cannot be resolved
	 * @see TypedStringValue#resolveTargetType
	 */
	@Nullable
	protected Class<?> resolveTargetType(TypedStringValue value) throws ClassNotFoundException {
		// 如果value有携带目标类型
		if (value.hasTargetType()) {
			// 返回value的目标类型
			return value.getTargetType();
		}
		return value.resolveTargetType(this.beanFactory.getBeanClassLoader());
	}

	/**
	 * Resolve a reference to another bean in the factory.
	 */
	@Nullable
	private Object resolveReference(Object argName, RuntimeBeanReference ref) {
		try {
			Object bean;
			Class<?> beanType = ref.getBeanType();
			if (ref.isToParent()) {
				BeanFactory parent = this.beanFactory.getParentBeanFactory();
				if (parent == null) {
					throw new BeanCreationException(
							this.beanDefinition.getResourceDescription(), this.beanName,
							"Cannot resolve reference to bean " + ref +
									" in parent factory: no parent factory available");
				}
				if (beanType != null) {
					bean = parent.getBean(beanType);
				}
				else {
					bean = parent.getBean(String.valueOf(doEvaluate(ref.getBeanName())));
				}
			}
			else {
				String resolvedName;
				if (beanType != null) {
					NamedBeanHolder<?> namedBean = this.beanFactory.resolveNamedBean(beanType);
					bean = namedBean.getBeanInstance();
					resolvedName = namedBean.getBeanName();
				}
				else {
					resolvedName = String.valueOf(doEvaluate(ref.getBeanName()));
					bean = this.beanFactory.getBean(resolvedName);
				}
				this.beanFactory.registerDependentBean(resolvedName, this.beanName);
			}
			if (bean instanceof NullBean) {
				bean = null;
			}
			return bean;
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					this.beanDefinition.getResourceDescription(), this.beanName,
					"Cannot resolve reference to bean '" + ref.getBeanName() + "' while setting " + argName, ex);
		}
	}

	/**
	 * Resolve an inner bean definition.
	 * @param argName the name of the argument that the inner bean is defined for
	 * @param innerBeanName the name of the inner bean
	 * @param innerBd the bean definition for the inner bean
	 * @return the resolved inner bean instance
	 */
	@Nullable
	private Object resolveInnerBean(Object argName, String innerBeanName, BeanDefinition innerBd) {
		// 定义一个用于保存innerBd域beanDefinition合并后的BeanDefinitoBeanDefinition对象的变量
		RootBeanDefinition mbd = null;
		try {
			// 获取innerBd域beanDefinition合并后的BeanDefinitoBeanDefinition对象
			mbd = this.beanFactory.getMergedBeanDefinition(innerBeanName, innerBd, this.beanDefinition);
			// Check given bean name whether it is unique. If not already unique,
			// add counter - increasing the counter until the name is unique.
			// 检查给定的bean名称是否唯一。如果不是唯一的，请添加计数器-增加计数器，直到名称唯一。
			// 解决内部bean名称冲突的问题
			// 定义实际的内部Bean名称，初始为innerBeanName
			String actualInnerBeanName = innerBeanName;
			// 如果mbd是单例的
			if (mbd.isSingleton()) {
				// 调整innerbeanName，直到该Bean名称在工厂中唯一。最后将结果赋值给actualInnerBeanName
				actualInnerBeanName = adaptInnerBeanName(innerBeanName);
			}
			// 将actualInnerBeanName和beanName的包含关系注册到工厂中
			this.beanFactory.registerContainedBean(actualInnerBeanName, this.beanName);
			// Guarantee initialization of beans that the inner bean depends on.
			// 确保内部Bean依赖的bean的初始化，获取mbd的要依赖的Bean名称
			String[] dependsOn = mbd.getDependsOn();
			// 如果有需要依赖的Bean名称
			if (dependsOn != null) {
				// 遍历dependsOn
				for (String dependsOnBean : dependsOn) {
					// 注册dependsOnBean与actualInnerBeanName的依赖关系到该工厂中
					this.beanFactory.registerDependentBean(dependsOnBean, actualInnerBeanName);
					// 获取dependsOnBean的Bean对象(不引用，只是为了让dependsOnBean所对应的Bean对象实例化)
					this.beanFactory.getBean(dependsOnBean);
				}
			}
			// Actually create the inner bean instance now...
			// 实际上现有创建内部bean实例，创建innerBeanName对应的Bean对象
			Object innerBean = this.beanFactory.createBean(actualInnerBeanName, mbd, null);
			// 如果innerBean是FactoryBean实例
			if (innerBean instanceof FactoryBean) {
				// mbds是否是"synthetic"的标记，一般是指只有AOP相关的prointCut配置或者Advice配置才会设置为true
				boolean synthetic = mbd.isSynthetic();
				// 从BeanFactory对象中获取管理的对象，只有mbd不是synthetic才对其对象进行该工厂的后置处理
				innerBean = this.beanFactory.getObjectFromFactoryBean(
						(FactoryBean<?>) innerBean, actualInnerBeanName, !synthetic);
			}
			if (innerBean instanceof NullBean) {
				innerBean = null;
			}
			return innerBean;
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					this.beanDefinition.getResourceDescription(), this.beanName,
					"Cannot create inner bean '" + innerBeanName + "' " +
					(mbd != null && mbd.getBeanClassName() != null ? "of type [" + mbd.getBeanClassName() + "] " : "") +
					"while setting " + argName, ex);
		}
	}

	/**
	 * Checks the given bean name whether it is unique. If not already unique,
	 * a counter is added, increasing the counter until the name is unique.
	 * @param innerBeanName the original name for the inner bean
	 * @return the adapted name for the inner bean
	 */
	private String adaptInnerBeanName(String innerBeanName) {
		// 定义一个内部Bean变量，初始为innerBean名
		String actualInnerBeanName = innerBeanName;
		// 定义一个用于计数的计数器，初始为0
		int counter = 0;
		// 获取前缀
		String prefix = innerBeanName + BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR;
		// 只要actualInnerBeanName在工厂中使用就继续循环，即actualInnerBeanName是否是别名
		// 或该工厂是否已包含actualInnerBeanName的bean对象，或 该工厂是否已经为actualInnerBeanName注册类依赖Bean关系
		while (this.beanFactory.isBeanNameInUse(actualInnerBeanName)) {
			// 计数器+1
			counter++;
			// 让actualInnerBeanName重新引用凭借后的字符串：innerBeanName + # + counter
			actualInnerBeanName = prefix + counter;
		}
		return actualInnerBeanName;
	}

	/**
	 * For each element in the managed array, resolve reference if necessary.
	 */
	private Object resolveManagedArray(Object argName, List<?> ml, Class<?> elementType) {
		Object resolved = Array.newInstance(elementType, ml.size());
		for (int i = 0; i < ml.size(); i++) {
			Array.set(resolved, i, resolveValueIfNecessary(new KeyedArgName(argName, i), ml.get(i)));
		}
		return resolved;
	}

	/**
	 * For each element in the managed list, resolve reference if necessary.
	 */
	private List<?> resolveManagedList(Object argName, List<?> ml) {
		// 定义一个用于存放解析后的实例对象的ArrayList，初始化长度为ml.size()
		List<Object> resolved = new ArrayList<>(ml.size());
		// 遍历ml
		for (int i = 0; i < ml.size(); i++) {
			// 获取第i个ml元素对象，解析出该元素对象的实例对象然后添加到resolved中
			resolved.add(resolveValueIfNecessary(new KeyedArgName(argName, i), ml.get(i)));
		}
		return resolved;
	}

	/**
	 * 解析ManagedSet对象，以得到解析后的Set对象并将结果返回
	 * For each element in the managed set, resolve reference if necessary.
	 */
	private Set<?> resolveManagedSet(Object argName, Set<?> ms) {
		Set<Object> resolved = new LinkedHashSet<>(ms.size());
		int i = 0;
		for (Object m : ms) {
			resolved.add(resolveValueIfNecessary(new KeyedArgName(argName, i), m));
			i++;
		}
		return resolved;
	}

	/**
	 * For each element in the managed map, resolve reference if necessary.
	 */
	private Map<?, ?> resolveManagedMap(Object argName, Map<?, ?> mm) {
		Map<Object, Object> resolved = CollectionUtils.newLinkedHashMap(mm.size());
		mm.forEach((key, value) -> {
			Object resolvedKey = resolveValueIfNecessary(argName, key);
			Object resolvedValue = resolveValueIfNecessary(new KeyedArgName(argName, key), value);
			resolved.put(resolvedKey, resolvedValue);
		});
		return resolved;
	}


	/**
	 * Holder class used for delayed toString building.
	 */
	private static class KeyedArgName {

		private final Object argName;

		private final Object key;

		public KeyedArgName(Object argName, Object key) {
			this.argName = argName;
			this.key = key;
		}

		@Override
		public String toString() {
			return this.argName + " with key " + BeanWrapper.PROPERTY_KEY_PREFIX +
					this.key + BeanWrapper.PROPERTY_KEY_SUFFIX;
		}
	}

}
