虚荣指标性设计：

1.	overview card，展示用户目前为止总共的notes数量以及已经坚持使用keenotes多少天数。
	1.	settings界面中新增一个与overview card展示与否的设置toggle，默认disable
	2.	notes总数量应该设计为一个全局reactive的property，这样，有更新的情况下也可以实时刷新展示
	3.	keenotes使用总天数的计算，不需要每次搜索数据库，可以在第一条note到达本地后，将其日期缓存，比如跟设置一样存储，每次启动的时候读取缓存并与当前日期对比计算即可。原则上，它也可以设计为reactive，比如用户长期开着keenotes，那跨天之后，这个总天数也应该变动。
2.	overview card的位置，
	1.	在desktop版里，放在keenotes logo下方以及note按钮上方（sidebar）
	2.	在android和ios版中，统一放在note输入框上方位置。



