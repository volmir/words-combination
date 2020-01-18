<template>
    <div>

<modal name="waiting-modal" :width="500" :height="120">
    <p class="text-center">
        <img src="@/assets/images/waiting.gif" width="40" border="0">
        <strong>Идет поиск комбинаций слов ...</strong>
    </p>
</modal>        

    </div>
</template>

<script>
export default {
    
}
</script>

<style scoped>
p.text-center {
    padding: 40px 0px 30px 0px;
}
p.text-center img {
    margin-right: 10px;
}
</style>