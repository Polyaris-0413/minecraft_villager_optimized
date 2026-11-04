# 待办

## 发布前

- [ ] `fabric.mod.json` 的 `description` 仍是模板文案（"This is an example description! ..."），
      换成三条功能的一句话简介
- [ ] `fabric.mod.json` 的 `contact.homepage` / `contact.sources` 仍指向 Fabric 模板仓库，
      换成自己的链接；没有就删掉这两个字段
- [ ] 重写 `README.md`（现在是 Fabric 模板的 Setup / License 说明）
- [ ] 换掉模板图标 `src/main/resources/assets/villageroptimized/icon.png`

## 可选增强

- [ ] 参数外置成配置文件：现在集中在 `VillagerSettings`，改数值要重新编译
- [ ] 铁匠修复加限制：附近有敌对生物 / 夜晚 / 自己受伤时不修
      （参考 [Helpful Armorers](https://modrinth.com/mod/helpful-armorers)）
- [ ] 只修"本村"的铁傀儡：按村庄 / POI 判断，而不只是 16 格距离
- [ ] 修复速率 25 HP/s 偏强（快过僵尸 4 HP/s），可按下调手感调低
